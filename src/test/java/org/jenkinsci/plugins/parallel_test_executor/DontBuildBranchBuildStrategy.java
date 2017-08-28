package org.jenkinsci.plugins.parallel_test_executor;

import hudson.Extension;
import jenkins.branch.BranchBuildStrategy;
import jenkins.branch.BranchBuildStrategyDescriptor;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class DontBuildBranchBuildStrategy extends BranchBuildStrategy {

  static final DontBuildBranchBuildStrategy INSTANCE = new DontBuildBranchBuildStrategy();

  @Override
  public boolean isAutomaticBuild(SCMSource source, SCMHead head) {
    // never ever build automatically
    return false;
  }

  @Extension
  public static class DescriptorImpl extends BranchBuildStrategyDescriptor {
    @Nonnull
    @Override
    public String getDisplayName() {
      return "Hello";
    }

    @Override
    public BranchBuildStrategy newInstance(@CheckForNull StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
      return INSTANCE;
    }
  }
}
