package org.jenkinsci.plugins.mavenrepocleaner;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.Launcher;
import hudson.maven.AbstractMavenProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.MasterToSlaveFileCallable;
import net.sf.json.JSONObject;

/**
 * @author: <a hef="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class MavenRepoCleanerPostBuildTask extends Recorder {
    private static final int DEFAULT_GRACE_PERIOD = 7;
    private static final String DEFAULT_CHANGING_ARTIFACTS_PATTERN = ".+-SNAPSHOT";
	private static final int DEFAULT_CHANGING_ARTIFACTS_MAX_AGE_IN_HOURS = -1;


	private int gracePeriodInDays;
	private String changingArtifactPatterns;
	private int changingArtifactMaxAgeInHours;

    @DataBoundConstructor
	public MavenRepoCleanerPostBuildTask(int gracePeriodInDays, String changingArtifactPatterns, int changingArtifactMaxAgeInHours) {
    	this.gracePeriodInDays = gracePeriodInDays;
    	setChangingArtifactPatterns(changingArtifactPatterns);
    	setChangingArtifactMaxAgeInHours(changingArtifactMaxAgeInHours);
    }

    @DataBoundSetter
    public void setChangingArtifactPatterns(String changingArtifactPatterns) {
		this.changingArtifactPatterns = changingArtifactPatterns;
		if (this.changingArtifactPatterns == null) {
			this.changingArtifactPatterns = "";
		}
    }

    @DataBoundSetter
	public void setChangingArtifactMaxAgeInHours(int changingArtifactMaxAgeInHours) {
		changingArtifactMaxAgeInHours = changingArtifactMaxAgeInHours;
	}

	public int getChangingArtifactMaxAgeInHours() {
		return changingArtifactMaxAgeInHours;
	}

	public String getChangingArtifactPatterns() {
		return changingArtifactPatterns;
	}

	/**
	 * @return the gracePeriodInDays
	 */
	public int getGracePeriodInDays() {
		return gracePeriodInDays;
	}

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        final long started = build.getTimeInMillis();
        AbstractProject<?,?> project = build.getProject();
        MavenRepoCleanerProperty mrcp = project.getProperty(MavenRepoCleanerProperty.class);
        long gracePeriodInMillis = gracePeriodInDays * 24 * 60 * 60 * 1000;
        long keepTimeStamp = Math.max(0, started - gracePeriodInMillis);

        FileCallableImpl cleanup = new FileCallableImpl(keepTimeStamp);
        Pattern[] patterns = compile(tokenize(getChangingArtifactPatterns()));
        cleanup.setChangingPatterns(patterns);
        cleanup.setChangingArtifactMaxAgeInHours(getChangingArtifactMaxAgeInHours());
        Collection<String> removed = build.getWorkspace().child(".repository").act(cleanup);
        if (removed.size() > 0) {
            listener.getLogger().println( removed.size() + " unused artifacts removed from private maven repository" );
        }
        return true;
    }

	private Pattern[] compile(String[] changingArtifactPatterns) throws PatternSyntaxException {
		Pattern[] result = new Pattern[changingArtifactPatterns.length];
		int i = 0;
		for (String pattern : changingArtifactPatterns) {
			result[i++] = Pattern.compile(pattern);
		}
		return result;
	}

    @Override
	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		public DescriptorImpl() {
            super(MavenRepoCleanerPostBuildTask.class);
        }

        @Override
        public String getDisplayName() {
            return "Cleanup maven repository for unused artifacts";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return FreeStyleProject.class.isAssignableFrom(jobType)
                    || AbstractMavenProject.class.isAssignableFrom(jobType);
        }

        public FormValidation doCheckGracePeriodInDays(@QueryParameter int gracePeriodInDays) {
        	if (gracePeriodInDays < 0) {
        		return FormValidation.error("Must be a positive value.");
        	}
        	return FormValidation.ok();
        }

        public FormValidation doCheckChangingArtifactPatterns(@QueryParameter String changingArtifactPatterns) {
        	for (String regex : tokenize(changingArtifactPatterns)) {
        		try {
        			Pattern.compile(regex);
        		} catch (PatternSyntaxException ex) {
        			return FormValidation.error("Not a valid regular expression: " + ex.getMessage());
        		}
			}
        	return FormValidation.ok();
        }

        @Override
        public Publisher newInstance(StaplerRequest aReq, JSONObject formData)
        		throws hudson.model.Descriptor.FormException {

            if (!formData.has("gracePeriodInDays")) {
            	formData.put("gracePeriodInDays", DEFAULT_GRACE_PERIOD);
            }
            if (!formData.has("changingArtifactPatterns")) {
            	formData.put("changingArtifactPatterns", DEFAULT_CHANGING_ARTIFACTS_PATTERN);
            }
            if (!formData.has("changingArtifactMaxAgeInHours")) {
            	formData.put("changingArtifactMaxAgeInHours", DEFAULT_CHANGING_ARTIFACTS_MAX_AGE_IN_HOURS);
            }
        	return super.newInstance(aReq, formData);
        }
    }

    private static class FileCallableImpl extends MasterToSlaveFileCallable<Collection<String>> {
        private final long keepTimestamp;
		private Pattern[] changingArtifactPatterns;
		private int changingArtifactMaxAgeInHours;

        public FileCallableImpl(long keepTimestamp) {
            this.keepTimestamp = keepTimestamp;
        }

        public void setChangingPatterns(Pattern[] changingArtifactPatterns) {
        	this.changingArtifactPatterns = changingArtifactPatterns;
        }

		public void setChangingArtifactMaxAgeInHours(int changingArtifactMaxAgeInHours) {
			this.changingArtifactMaxAgeInHours = changingArtifactMaxAgeInHours;
		}

		@Override
		public Collection<String> invoke(File repository, VirtualChannel channel) throws IOException, InterruptedException {
            return new RepositoryCleaner(keepTimestamp, changingArtifactPatterns, changingArtifactMaxAgeInHours).clean(repository);
        }
    }

	public static String[] tokenize(String string) {
		return string.split("\\s+");
	}
}
