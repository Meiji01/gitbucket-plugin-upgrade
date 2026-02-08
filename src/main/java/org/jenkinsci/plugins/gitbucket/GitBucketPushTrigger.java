/*
 * The MIT License
 *
 * Copyright (c) 2013, Seiji Sogabe
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.gitbucket;

import hudson.Extension;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.plugins.git.RevisionParameterAction;
import hudson.triggers.SCMTrigger.SCMTriggerCause;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.SequentialExecutionQueue;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins.MasterComputer;
import jenkins.model.Jenkins;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.gitbucket.GitBucketPushRequest.Commit;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Triggers a build when we receive a GitBucket WebHook.
 *
 * Supports traditional jobs and Pipeline (WorkflowJob) by using reflection
 * to invoke scheduling methods when available and falling back to the
 * Jenkins queue scheduling.
 */
public class GitBucketPushTrigger extends Trigger<Job<?, ?>> {

    private boolean passThroughGitCommit;

    @DataBoundConstructor
    public GitBucketPushTrigger(boolean passThroughGitCommit) {
        this.passThroughGitCommit = passThroughGitCommit;
    }

    public boolean isPassThroughGitCommit() {
        return passThroughGitCommit;
    }

    public void onPost(final GitBucketPushRequest req) {
        getDescriptor().queue.execute(new Runnable() {
            @Override
            public void run() {
                final Job<?, ?> theJob = job;
                if (theJob == null) {
                    LOGGER.log(Level.WARNING, "Cannot trigger build - job is null");
                    return;
                }
                
                // Write to log file for the Hook Log view
                PrintStream logger = null;
                try {
                    logger = new PrintStream(getLogFile(), "UTF-8");
                    logger.println("Started on " + DateFormat.getDateTimeInstance().format(new Date()));
                    logger.println("GitBucket push webhook received from repository: " + 
                                 (req.getRepository() != null ? req.getRepository().getUrl() : "unknown"));
                    if (req.getPusher() != null) {
                        logger.println("Pushed by: " + req.getPusher().getName());
                    }
                    logger.println("Branch: " + req.getRef());
                    if (req.getLastCommit() != null) {
                        logger.println("Last commit: " + req.getLastCommit().getId());
                        logger.println("Commit message: " + req.getLastCommit().getMessage());
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to write webhook log", e);
                } finally {
                    if (logger != null) {
                        logger.close();
                    }
                }
                
                LOGGER.log(Level.INFO, "{0} triggered.", theJob.getName());
                String name = " #" + theJob.getNextBuildNumber();
                GitBucketPushCause cause = createGitBucketPushCause(req);
                Action[] actions = createActions(req, cause);

                boolean scheduled = false;
                try {
                    int quietPeriod = getQuietPeriod(theJob);
                    
                    // 1) try scheduleBuild2(int, Action...) - works for both AbstractProject and WorkflowJob
                    try {
                        Method m = theJob.getClass().getMethod("scheduleBuild2", int.class, Action[].class);
                        Object future = m.invoke(theJob, quietPeriod, (Object) actions);
                        scheduled = future != null;
                        if (scheduled) {
                            LOGGER.log(Level.FINE, "Scheduled using scheduleBuild2(int, Action[])");
                        }
                    } catch (NoSuchMethodException e1) {
                        // 2) try scheduleBuild2(int, Cause, Action...)
                        try {
                            Method m2 = theJob.getClass().getMethod("scheduleBuild2", int.class, Cause.class, Action[].class);
                            Object future = m2.invoke(theJob, quietPeriod, cause, (Object) actions);
                            scheduled = future != null;
                            if (scheduled) {
                                LOGGER.log(Level.FINE, "Scheduled using scheduleBuild2(int, Cause, Action[])");
                            }
                        } catch (NoSuchMethodException e2) {
                            // 3) try scheduleBuild(int, Cause, Action...)
                            try {
                                Method m3 = theJob.getClass().getMethod("scheduleBuild", int.class, Cause.class, Action[].class);
                                Object r = m3.invoke(theJob, quietPeriod, cause, (Object) actions);
                                scheduled = (r instanceof Boolean) && (Boolean) r;
                                if (scheduled) {
                                    LOGGER.log(Level.FINE, "Scheduled using scheduleBuild(int, Cause, Action[])");
                                }
                            } catch (NoSuchMethodException e3) {
                                // 4) fallback: use Jenkins queue schedule with actions
                                List<Action> actionList = new ArrayList<Action>();
                                actionList.addAll(java.util.Arrays.asList(actions));
                                Queue.Item item = Jenkins.getInstance().getQueue().schedule2((Queue.Task) theJob, quietPeriod, actionList).getItem();
                                scheduled = item != null;
                                if (scheduled) {
                                    LOGGER.log(Level.FINE, "Scheduled using Queue.schedule2");
                                }
                            }
                        }
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    LOGGER.log(Level.WARNING, "Failed to schedule build for job: {0}", e.getMessage());
                    e.printStackTrace();
                    scheduled = false;
                }

                if (scheduled) {
                    LOGGER.log(Level.INFO, "Triggered {0} for {1}", new Object[]{name, theJob.getName()});
                } else {
                    LOGGER.log(Level.WARNING, "Job {0} could not be scheduled (may already be in queue).", theJob.getName());
                }
            }

            private GitBucketPushCause createGitBucketPushCause(GitBucketPushRequest req) {
                String triggeredByUser = req.getPusher() == null ? null : req.getPusher().getName();
                return new GitBucketPushCause(triggeredByUser);
            }

            private Action[] createActions(GitBucketPushRequest req, GitBucketPushCause cause) {
                List<Action> actions = new ArrayList<Action>();

                // add the cause action so scheduling APIs that accept actions can see the cause
                actions.add(new CauseAction(cause));

                if (passThroughGitCommit) {
                    Commit lastCommit = req.getLastCommit();
                    if (lastCommit != null) {
                        actions.add(new RevisionParameterAction(lastCommit.getId(), false));
                    }
                }

                return actions.toArray(new Action[0]);
            }

            private int getQuietPeriod(Job<?, ?> job) {
                try {
                    Method m = job.getClass().getMethod("getQuietPeriod");
                    Object o = m.invoke(job);
                    if (o instanceof Integer) {
                        return (Integer) o;
                    }
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    // Use default quiet period if reflection fails
                }
                return 0;
            }
        });
    }

    public static class GitBucketPushCause extends SCMTriggerCause {

        private final String pushedBy;

        public GitBucketPushCause(String pushedBy) {
            this(pushedBy, "");
        }

        public GitBucketPushCause(String pushedBy, File logFile) throws IOException {
            super(logFile);
            this.pushedBy = pushedBy;
        }

        public GitBucketPushCause(String pushedBy, String pollingLog) {
            super(pollingLog);
            this.pushedBy = pushedBy;
        }

        @Override
        public String getShortDescription() {
            if (pushedBy == null) {
                return "Started by GitBucket push";
            } else {
                return String.format("Started by GitBucket push by %s", pushedBy);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }
            if (!(o instanceof GitBucketPushCause)) {
                return false;
            }
            GitBucketPushCause that = (GitBucketPushCause) o;
            return pushedBy != null ? pushedBy.equals(that.pushedBy) : that.pushedBy == null;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (pushedBy != null ? pushedBy.hashCode() : 0);
            return result;
        }
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        return Collections.singletonList(new GitBucketWebHookPollingAction());
    }

    public class GitBucketWebHookPollingAction implements Action {

        public Job<?, ?> getOwner() {
            return job;
        }

        @Override
        public String getIconFileName() {
            return "/plugin/gitbucket/images/24x24/gitbucket-log.png";
        }

        @Override
        public String getDisplayName() {
            return "GitBucket Hook Log";
        }

        @Override
        public String getUrlName() {
            return "GitBucketPollLog";
        }

        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        public void writeLogTo(XMLOutput out) throws IOException {
            java.io.StringWriter sw = new java.io.StringWriter();
            try {
                long bytesWritten = new AnnotatedLargeText<GitBucketWebHookPollingAction>(
                        getLogFile(), Charset.defaultCharset(), true, this).writeHtmlTo(0, sw);
                out.write(sw.toString());
                LOGGER.log(Level.FINE, "Wrote {0} bytes of log output", bytesWritten);
            } catch (Throwable e) {
                throw new IOException("Failed to write HTML log", e);
            }
        }
    }

    @Override
    public GitBucketPushTriggerDescriptor getDescriptor() {
        return (GitBucketPushTriggerDescriptor) super.getDescriptor();
    }

    public File getLogFile() {
        if (job != null) {
            try {
                Method getRoot = job.getClass().getMethod("getRootDir");
                Object root = getRoot.invoke(job);
                if (root instanceof File) {
                    return new File((File) root, "gitbucket-polling.log");
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                // Fall back to Jenkins root dir if reflection fails
            }
        }
        // Default: use Jenkins root directory
        return new File(jenkins.model.Jenkins.getInstance().getRootDir(), "gitbucket-polling.log");
    }

    @Extension
    public static class GitBucketPushTriggerDescriptor extends TriggerDescriptor {

        private transient final SequentialExecutionQueue queue
                = new SequentialExecutionQueue(MasterComputer.threadPoolForRemoting);

        @Override
        public boolean isApplicable(Item item) {
            // Applicable to traditional projects and pipeline (WorkflowJob)
            try {
                if (item instanceof hudson.model.AbstractProject) {
                    return true;
                }
                Class<?> workflowJobClass = Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowJob");
                if (workflowJobClass.isInstance(item)) {
                    return true;
                }
            } catch (ClassNotFoundException e) {
                // workflow plugin not installed
            }
            return false;
        }

        @Override
        public String getDisplayName() {
            return "Build when a change is pushed to GitBucket";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/gitbucket/help/help-trigger.html";
        }

    }

    private static final Logger LOGGER = Logger.getLogger(GitBucketPushTrigger.class.getName());
}