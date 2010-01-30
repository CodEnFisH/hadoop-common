/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.ClusterMetrics;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.QueueInfo;
import org.apache.hadoop.mapreduce.TaskCompletionEvent;
import org.apache.hadoop.mapreduce.TaskTrackerInfo;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.filecache.TaskDistributedCacheManager;
import org.apache.hadoop.mapreduce.filecache.TrackerDistributedCacheManager;
import org.apache.hadoop.mapreduce.protocol.ClientProtocol;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.security.TokenStorage;
import org.apache.hadoop.mapreduce.server.jobtracker.JTConfig;
import org.apache.hadoop.mapreduce.server.jobtracker.State;
import org.apache.hadoop.mapreduce.split.SplitMetaInfoReader;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitMetaInfo;
import org.apache.hadoop.security.UserGroupInformation;

/** Implements MapReduce locally, in-process, for debugging. */ 
public class LocalJobRunner implements ClientProtocol {
  public static final Log LOG =
    LogFactory.getLog(LocalJobRunner.class);

  /** The maximum number of map tasks to run in parallel in LocalJobRunner */
  public static final String LOCAL_MAX_MAPS =
    "mapreduce.local.map.tasks.maximum";

  private FileSystem fs;
  private HashMap<JobID, Job> jobs = new HashMap<JobID, Job>();
  private JobConf conf;
  private AtomicInteger map_tasks = new AtomicInteger(0);
  private int reduce_tasks = 0;
  final Random rand = new Random();
  
  private JobTrackerInstrumentation myMetrics = null;

  private static final String jobDir =  "localRunner/";

  private static final Counters EMPTY_COUNTERS = new Counters();

  public long getProtocolVersion(String protocol, long clientVersion) {
    return ClientProtocol.versionID;
  }

  private class Job extends Thread implements TaskUmbilicalProtocol {
    // The job directory on the system: JobClient places job configurations here.
    // This is analogous to JobTracker's system directory.
    private Path systemJobDir;
    private Path systemJobFile;
    
    // The job directory for the task.  Analagous to a task's job directory.
    private Path localJobDir;
    private Path localJobFile;

    private JobID id;
    private JobConf job;

    private int numMapTasks;
    private float [] partialMapProgress;
    private Counters [] mapCounters;
    private Counters reduceCounters;

    private JobStatus status;
    private List<TaskAttemptID> mapIds = Collections.synchronizedList(
        new ArrayList<TaskAttemptID>());

    private JobProfile profile;
    private FileSystem localFs;
    boolean killed = false;
    
    private TrackerDistributedCacheManager trackerDistributerdCacheManager;
    private TaskDistributedCacheManager taskDistributedCacheManager;

    public long getProtocolVersion(String protocol, long clientVersion) {
      return TaskUmbilicalProtocol.versionID;
    }
    
    public Job(JobID jobid, String jobSubmitDir) throws IOException {
      this.systemJobDir = new Path(jobSubmitDir);
      this.systemJobFile = new Path(systemJobDir, "job.xml");
      this.id = jobid;
      JobConf conf = new JobConf(systemJobFile);
      this.localFs = FileSystem.getLocal(conf);
      this.localJobDir = localFs.makeQualified(conf.getLocalPath(jobDir));
      this.localJobFile = new Path(this.localJobDir, id + ".xml");

      // Manage the distributed cache.  If there are files to be copied,
      // this will trigger localFile to be re-written again.
      this.trackerDistributerdCacheManager =
          new TrackerDistributedCacheManager(conf, new DefaultTaskController());
      this.taskDistributedCacheManager = 
          trackerDistributerdCacheManager.newTaskDistributedCacheManager(conf);
      taskDistributedCacheManager.setup(
          new LocalDirAllocator(MRConfig.LOCAL_DIR), 
          new File(systemJobDir.toString()),
          "archive", "archive");
      
      if (DistributedCache.getSymlink(conf)) {
        // This is not supported largely because, 
        // for a Child subprocess, the cwd in LocalJobRunner
        // is not a fresh slate, but rather the user's working directory.
        // This is further complicated because the logic in
        // setupWorkDir only creates symlinks if there's a jarfile
        // in the configuration.
        LOG.warn("LocalJobRunner does not support " +
        		"symlinking into current working dir.");
      }
      // Setup the symlinks for the distributed cache.
      TaskRunner.setupWorkDir(conf, new File(localJobDir.toUri()).getAbsoluteFile());
      
      // Write out configuration file.  Instead of copying it from
      // systemJobFile, we re-write it, since setup(), above, may have
      // updated it.
      OutputStream out = localFs.create(localJobFile);
      try {
        conf.writeXml(out);
      } finally {
        out.close();
      }
      this.job = new JobConf(localJobFile);

      // Job (the current object) is a Thread, so we wrap its class loader.
      if (!taskDistributedCacheManager.getClassPaths().isEmpty()) {
        setContextClassLoader(taskDistributedCacheManager.makeClassLoader(
                getContextClassLoader()));
      }
      
      profile = new JobProfile(job.getUser(), id, systemJobFile.toString(), 
                               "http://localhost:8080/", job.getJobName());
      status = new JobStatus(id, 0.0f, 0.0f, JobStatus.RUNNING, 
          profile.getUser(), profile.getJobName(), profile.getJobFile(), 
          profile.getURL().toString());

      jobs.put(id, this);

      this.start();
    }

    JobProfile getProfile() {
      return profile;
    }

    /**
     * A Runnable instance that handles a map task to be run by an executor.
     */
    protected class MapTaskRunnable implements Runnable {
      private final int taskId;
      private final TaskSplitMetaInfo info;
      private final JobID jobId;
      private final JobConf localConf;

      // This is a reference to a shared object passed in by the
      // external context; this delivers state to the reducers regarding
      // where to fetch mapper outputs.
      private final Map<TaskAttemptID, MapOutputFile> mapOutputFiles;

      public volatile Throwable storedException;

      public MapTaskRunnable(TaskSplitMetaInfo info, int taskId, JobID jobId,
          Map<TaskAttemptID, MapOutputFile> mapOutputFiles) {
        this.info = info;
        this.taskId = taskId;
        this.mapOutputFiles = mapOutputFiles;
        this.jobId = jobId;
        this.localConf = new JobConf(job);
      }

      public void run() {
        try {
          TaskAttemptID mapId = new TaskAttemptID(new TaskID(
              jobId, TaskType.MAP, taskId), 0);
          LOG.info("Starting task: " + mapId);
          mapIds.add(mapId);
          MapTask map = new MapTask(systemJobFile.toString(), mapId, taskId,
            info.getSplitIndex(), 1);
          map.setUser(UserGroupInformation.getCurrentUser().
              getShortUserName());
          TaskRunner.setupChildMapredLocalDirs(map, localConf);

          MapOutputFile mapOutput = new MapOutputFile();
          mapOutput.setConf(localConf);
          mapOutputFiles.put(mapId, mapOutput);

          map.setJobFile(localJobFile.toString());
          localConf.setUser(map.getUser());
          map.localizeConfiguration(localConf);
          map.setConf(localConf);
          try {
            map_tasks.getAndIncrement();
            myMetrics.launchMap(mapId);
            map.run(localConf, Job.this);
            myMetrics.completeMap(mapId);
          } finally {
            map_tasks.getAndDecrement();
          }

          LOG.info("Finishing task: " + mapId);
        } catch (Throwable e) {
          this.storedException = e;
        }
      }
    }

    /**
     * Create Runnables to encapsulate map tasks for use by the executor
     * service.
     * @param taskInfo Info about the map task splits
     * @param jobId the job id
     * @param mapOutputFiles a mapping from task attempts to output files
     * @return a List of Runnables, one per map task.
     */
    protected List<MapTaskRunnable> getMapTaskRunnables(
        TaskSplitMetaInfo [] taskInfo, JobID jobId,
        Map<TaskAttemptID, MapOutputFile> mapOutputFiles) {

      int numTasks = 0;
      ArrayList<MapTaskRunnable> list = new ArrayList<MapTaskRunnable>();
      for (TaskSplitMetaInfo task : taskInfo) {
        list.add(new MapTaskRunnable(task, numTasks++, jobId,
            mapOutputFiles));
      }

      return list;
    }

    /**
     * Initialize the counters that will hold partial-progress from
     * the various task attempts.
     * @param numMaps the number of map tasks in this job.
     */
    private synchronized void initCounters(int numMaps) {
      // Initialize state trackers for all map tasks.
      this.partialMapProgress = new float[numMaps];
      this.mapCounters = new Counters[numMaps];
      for (int i = 0; i < numMaps; i++) {
        this.mapCounters[i] = EMPTY_COUNTERS;
      }

      this.reduceCounters = EMPTY_COUNTERS;
    }

    /**
     * Creates the executor service used to run map tasks.
     *
     * @param numMapTasks the total number of map tasks to be run
     * @return an ExecutorService instance that handles map tasks
     */
    protected ExecutorService createMapExecutor(int numMapTasks) {

      // Determine the size of the thread pool to use
      int maxMapThreads = job.getInt(LOCAL_MAX_MAPS, 1);
      if (maxMapThreads < 1) {
        throw new IllegalArgumentException(
            "Configured " + LOCAL_MAX_MAPS + " must be >= 1");
      }
      this.numMapTasks = numMapTasks;
      maxMapThreads = Math.min(maxMapThreads, this.numMapTasks);
      maxMapThreads = Math.max(maxMapThreads, 1); // In case of no tasks.

      initCounters(this.numMapTasks);

      LOG.debug("Starting thread pool executor.");
      LOG.debug("Max local threads: " + maxMapThreads);
      LOG.debug("Map tasks to process: " + this.numMapTasks);

      // Create a new executor service to drain the work queue.
      ExecutorService executor = Executors.newFixedThreadPool(maxMapThreads);

      return executor;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
      JobID jobId = profile.getJobID();
      JobContext jContext = new JobContextImpl(job, jobId);
      OutputCommitter outputCommitter = job.getOutputCommitter();

      try {
        TaskSplitMetaInfo[] taskSplitMetaInfos = 
          SplitMetaInfoReader.readSplitMetaInfo(jobId, localFs, conf, systemJobDir);

        int numReduceTasks = job.getNumReduceTasks();
        if (numReduceTasks > 1 || numReduceTasks < 0) {
          // we only allow 0 or 1 reducer in local mode
          numReduceTasks = 1;
          job.setNumReduceTasks(1);
        }
        outputCommitter.setupJob(jContext);
        status.setSetupProgress(1.0f);

        Map<TaskAttemptID, MapOutputFile> mapOutputFiles =
            Collections.synchronizedMap(new HashMap<TaskAttemptID, MapOutputFile>());

        List<MapTaskRunnable> taskRunnables = getMapTaskRunnables(taskSplitMetaInfos,
            jobId, mapOutputFiles);
        ExecutorService mapService = createMapExecutor(taskRunnables.size());

        // Start populating the executor with work units.
        // They may begin running immediately (in other threads).
        for (Runnable r : taskRunnables) {
          mapService.submit(r);
        }

        try {
          mapService.shutdown(); // Instructs queue to drain.

          // Wait for tasks to finish; do not use a time-based timeout.
          // (See http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6179024)
          LOG.info("Waiting for map tasks");
          mapService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ie) {
          // Cancel all threads.
          mapService.shutdownNow();
          throw ie;
        }

        LOG.info("Map task executor complete.");

        // After waiting for the map tasks to complete, if any of these
        // have thrown an exception, rethrow it now in the main thread context.
        for (MapTaskRunnable r : taskRunnables) {
          if (r.storedException != null) {
            throw new Exception(r.storedException);
          }
        }

        TaskAttemptID reduceId =
          new TaskAttemptID(new TaskID(jobId, TaskType.REDUCE, 0), 0);
        try {
          if (numReduceTasks > 0) {
            ReduceTask reduce = new ReduceTask(systemJobFile.toString(), 
                reduceId, 0, mapIds.size(), 1);
            reduce.setUser(UserGroupInformation.getCurrentUser().
                getShortUserName());
            JobConf localConf = new JobConf(job);
            localConf.set("mapreduce.jobtracker.address", "local");
            TaskRunner.setupChildMapredLocalDirs(reduce, localConf);
            // move map output to reduce input  
            for (int i = 0; i < mapIds.size(); i++) {
              if (!this.isInterrupted()) {
                TaskAttemptID mapId = mapIds.get(i);
                Path mapOut = mapOutputFiles.get(mapId).getOutputFile();
                MapOutputFile localOutputFile = new MapOutputFile();
                localOutputFile.setConf(localConf);
                Path reduceIn =
                  localOutputFile.getInputFileForWrite(mapId.getTaskID(),
                        localFs.getFileStatus(mapOut).getLen());
                if (!localFs.mkdirs(reduceIn.getParent())) {
                  throw new IOException("Mkdirs failed to create "
                      + reduceIn.getParent().toString());
                }
                if (!localFs.rename(mapOut, reduceIn))
                  throw new IOException("Couldn't rename " + mapOut);
              } else {
                throw new InterruptedException();
              }
            }
            if (!this.isInterrupted()) {
              reduce.setJobFile(localJobFile.toString());
              localConf.setUser(reduce.getUser());
              reduce.localizeConfiguration(localConf);
              reduce.setConf(localConf);
              reduce_tasks += 1;
              myMetrics.launchReduce(reduce.getTaskID());
              reduce.run(localConf, this);
              myMetrics.completeReduce(reduce.getTaskID());
              reduce_tasks -= 1;
            } else {
              throw new InterruptedException();
            }
          }
        } finally {
          for (MapOutputFile output : mapOutputFiles.values()) {
            output.removeAll();
          }
        }
        // delete the temporary directory in output directory
        outputCommitter.commitJob(jContext);
        status.setCleanupProgress(1.0f);

        if (killed) {
          this.status.setRunState(JobStatus.KILLED);
        } else {
          this.status.setRunState(JobStatus.SUCCEEDED);
        }

        JobEndNotifier.localRunnerNotification(job, status);

      } catch (Throwable t) {
        try {
          outputCommitter.abortJob(jContext, 
            org.apache.hadoop.mapreduce.JobStatus.State.FAILED);
        } catch (IOException ioe) {
          LOG.info("Error cleaning up job:" + id);
        }
        status.setCleanupProgress(1.0f);
        if (killed) {
          this.status.setRunState(JobStatus.KILLED);
        } else {
          this.status.setRunState(JobStatus.FAILED);
        }
        LOG.warn(id, t);

        JobEndNotifier.localRunnerNotification(job, status);

      } finally {
        try {
          fs.delete(systemJobFile.getParent(), true);  // delete submit dir
          localFs.delete(localJobFile, true);              // delete local copy
          // Cleanup distributed cache
          taskDistributedCacheManager.release();
          trackerDistributerdCacheManager.purgeCache();
        } catch (IOException e) {
          LOG.warn("Error cleaning up "+id+": "+e);
        }
      }
    }

    // TaskUmbilicalProtocol methods

    public JvmTask getTask(JvmContext context) { return null; }
    
    public synchronized boolean statusUpdate(TaskAttemptID taskId,
        TaskStatus taskStatus) throws IOException, InterruptedException {
      LOG.info(taskStatus.getStateString());
      int taskIndex = mapIds.indexOf(taskId);
      if (taskIndex >= 0) {                       // mapping
        float numTasks = (float) this.numMapTasks;

        partialMapProgress[taskIndex] = taskStatus.getProgress();
        mapCounters[taskIndex] = taskStatus.getCounters();

        float partialProgress = 0.0f;
        for (float f : partialMapProgress) {
          partialProgress += f;
        }
        status.setMapProgress(partialProgress / numTasks);
      } else {
        reduceCounters = taskStatus.getCounters();
        status.setReduceProgress(taskStatus.getProgress());
      }

      // ignore phase
      return true;
    }

    /** Return the current values of the counters for this job,
     * including tasks that are in progress.
     */
    public synchronized Counters getCurrentCounters() {
      if (null == mapCounters) {
        // Counters not yet initialized for job.
        return EMPTY_COUNTERS;
      }

      Counters current = EMPTY_COUNTERS;
      for (Counters c : mapCounters) {
        current = Counters.sum(current, c);
      }
      current = Counters.sum(current, reduceCounters);
      return current;
    }

    /**
     * Task is reporting that it is in commit_pending
     * and it is waiting for the commit Response
     */
    public void commitPending(TaskAttemptID taskid,
                              TaskStatus taskStatus) 
    throws IOException, InterruptedException {
      statusUpdate(taskid, taskStatus);
    }

    public void reportDiagnosticInfo(TaskAttemptID taskid, String trace) {
      // Ignore for now
    }
    
    public void reportNextRecordRange(TaskAttemptID taskid, 
        SortedRanges.Range range) throws IOException {
      LOG.info("Task " + taskid + " reportedNextRecordRange " + range);
    }

    public boolean ping(TaskAttemptID taskid) throws IOException {
      return true;
    }
    
    public boolean canCommit(TaskAttemptID taskid) 
    throws IOException {
      return true;
    }
    
    public void done(TaskAttemptID taskId) throws IOException {
      int taskIndex = mapIds.indexOf(taskId);
      if (taskIndex >= 0) {                       // mapping
        status.setMapProgress(1.0f);
      } else {
        status.setReduceProgress(1.0f);
      }
    }

    public synchronized void fsError(TaskAttemptID taskId, String message) 
    throws IOException {
      LOG.fatal("FSError: "+ message + "from task: " + taskId);
    }

    public void shuffleError(TaskAttemptID taskId, String message) throws IOException {
      LOG.fatal("shuffleError: "+ message + "from task: " + taskId);
    }
    
    public synchronized void fatalError(TaskAttemptID taskId, String msg) 
    throws IOException {
      LOG.fatal("Fatal: "+ msg + "from task: " + taskId);
    }
    
    public MapTaskCompletionEventsUpdate getMapCompletionEvents(JobID jobId, 
        int fromEventId, int maxLocs, TaskAttemptID id) throws IOException {
      return new MapTaskCompletionEventsUpdate(
        org.apache.hadoop.mapred.TaskCompletionEvent.EMPTY_ARRAY, false);
    }
    
  }

  public LocalJobRunner(Configuration conf) throws IOException {
    this(new JobConf(conf));
  }

  @Deprecated
  public LocalJobRunner(JobConf conf) throws IOException {
    this.fs = FileSystem.getLocal(conf);
    this.conf = conf;
    myMetrics = new JobTrackerMetricsInst(null, new JobConf(conf));
  }

  // JobSubmissionProtocol methods

  private static int jobid = 0;
  public synchronized org.apache.hadoop.mapreduce.JobID getNewJobID() {
    return new org.apache.hadoop.mapreduce.JobID("local", ++jobid);
  }

  public org.apache.hadoop.mapreduce.JobStatus submitJob(
      org.apache.hadoop.mapreduce.JobID jobid, String jobSubmitDir, TokenStorage ts) 
      throws IOException {
    TokenCache.setTokenStorage(ts);
    return new Job(JobID.downgrade(jobid), jobSubmitDir).status;
  }

  public void killJob(org.apache.hadoop.mapreduce.JobID id) {
    jobs.get(id).killed = true;
    jobs.get(id).interrupt();
  }

  public void setJobPriority(org.apache.hadoop.mapreduce.JobID id,
      String jp) throws IOException {
    throw new UnsupportedOperationException("Changing job priority " +
                      "in LocalJobRunner is not supported.");
  }
  
  /** Throws {@link UnsupportedOperationException} */
  public boolean killTask(org.apache.hadoop.mapreduce.TaskAttemptID taskId,
      boolean shouldFail) throws IOException {
    throw new UnsupportedOperationException("Killing tasks in " +
    "LocalJobRunner is not supported");
  }

  public org.apache.hadoop.mapreduce.TaskReport[] getTaskReports(
      org.apache.hadoop.mapreduce.JobID id, TaskType type) {
    return new org.apache.hadoop.mapreduce.TaskReport[0];
  }

  public org.apache.hadoop.mapreduce.JobStatus getJobStatus(
      org.apache.hadoop.mapreduce.JobID id) {
    Job job = jobs.get(JobID.downgrade(id));
    if(job != null)
      return job.status;
    else 
      return null;
  }
  
  public org.apache.hadoop.mapreduce.Counters getJobCounters(
      org.apache.hadoop.mapreduce.JobID id) {
    Job job = jobs.get(JobID.downgrade(id));

    return new org.apache.hadoop.mapreduce.Counters(job.getCurrentCounters());
  }

  public String getFilesystemName() throws IOException {
    return fs.getUri().toString();
  }
  
  public ClusterMetrics getClusterMetrics() {
    int numMapTasks = map_tasks.get();
    return new ClusterMetrics(numMapTasks, reduce_tasks, numMapTasks,
        reduce_tasks, 0, 0, 1, 1, jobs.size(), 1, 0, 0);
  }

  public State getJobTrackerState() throws IOException, InterruptedException {
    return State.RUNNING;
  }

  public long getTaskTrackerExpiryInterval() throws IOException, InterruptedException {
    return 0;
  }

  /** 
   * Get all active trackers in cluster. 
   * @return array of TaskTrackerInfo
   */
  public TaskTrackerInfo[] getActiveTrackers() 
      throws IOException, InterruptedException {
    return null;
  }

  /** 
   * Get all blacklisted trackers in cluster. 
   * @return array of TaskTrackerInfo
   */
  public TaskTrackerInfo[] getBlacklistedTrackers() 
      throws IOException, InterruptedException {
    return null;
  }

  public TaskCompletionEvent[] getTaskCompletionEvents(
      org.apache.hadoop.mapreduce.JobID jobid
      , int fromEventId, int maxEvents) throws IOException {
    return TaskCompletionEvent.EMPTY_ARRAY;
  }
  
  public org.apache.hadoop.mapreduce.JobStatus[] getAllJobs() {return null;}

  
  /**
   * Returns the diagnostic information for a particular task in the given job.
   * To be implemented
   */
  public String[] getTaskDiagnostics(
      org.apache.hadoop.mapreduce.TaskAttemptID taskid) throws IOException{
	  return new String [0];
  }

  /**
   * @see org.apache.hadoop.mapreduce.protocol.ClientProtocol#getSystemDir()
   */
  public String getSystemDir() {
    Path sysDir = new Path(
      conf.get(JTConfig.JT_SYSTEM_DIR, "/tmp/hadoop/mapred/system"));  
    return fs.makeQualified(sysDir).toString();
  }

  /**
   * @see org.apache.hadoop.mapreduce.protocol.ClientProtocol#getStagingAreaDir()
   */
  public String getStagingAreaDir() throws IOException {
    Path stagingRootDir = new Path(conf.get(JTConfig.JT_STAGING_AREA_ROOT, 
        "/tmp/hadoop/mapred/staging"));
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    String user;
    if (ugi != null) {
      user = ugi.getUserName() + rand.nextInt();
    } else {
      user = "dummy" + rand.nextInt();
    }
    return fs.makeQualified(new Path(stagingRootDir, user+"/.staging")).toString();
  }
  
  public String getJobHistoryDir() {
    return null;
  }

  @Override
  public QueueInfo[] getChildQueues(String queueName) throws IOException {
    return null;
  }

  @Override
  public QueueInfo[] getRootQueues() throws IOException {
    return null;
  }

  @Override
  public QueueInfo[] getQueues() throws IOException {
    return null;
  }


  @Override
  public QueueInfo getQueue(String queue) throws IOException {
    return null;
  }

  @Override
  public org.apache.hadoop.mapreduce.QueueAclsInfo[] 
      getQueueAclsForCurrentUser() throws IOException{
    return null;
  }

  /**
   * Set the max number of map tasks to run concurrently in the LocalJobRunner.
   * @param job the job to configure
   * @param maxMaps the maximum number of map tasks to allow.
   */
  public static void setLocalMaxRunningMaps(
      org.apache.hadoop.mapreduce.JobContext job,
      int maxMaps) {
    job.getConfiguration().setInt(LOCAL_MAX_MAPS, maxMaps);
  }

  /**
   * @return the max number of map tasks to run concurrently in the
   * LocalJobRunner.
   */
  public static int getLocalMaxRunningMaps(
      org.apache.hadoop.mapreduce.JobContext job) {
    return job.getConfiguration().getInt(LOCAL_MAX_MAPS, 1);
  }
}
