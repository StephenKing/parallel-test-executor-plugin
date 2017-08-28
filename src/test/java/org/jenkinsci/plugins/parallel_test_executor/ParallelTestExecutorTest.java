package org.jenkinsci.plugins.parallel_test_executor;

import hudson.Extension;
import hudson.model.FreeStyleProject;
import jenkins.branch.BranchBuildStrategy;
import jenkins.branch.BranchBuildStrategyDescriptor;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;
import org.kohsuke.stapler.StaplerRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class ParallelTestExecutorTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();
    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    @Test
    @LocalData
    public void xmlWithNoAddJUnitPublisherIsLoadedCorrectly() throws Exception {
        FreeStyleProject p = (FreeStyleProject) jenkinsRule.jenkins.getItem("old");
        ParallelTestExecutor trigger = (ParallelTestExecutor) p.getBuilders().get(0);

        assertTrue(trigger.isArchiveTestResults());
    }

    @Test
    public void workflowGenerateInclusions() throws Exception {
        new SnippetizerTester(jenkinsRule).assertRoundTrip(new SplitStep(new CountDrivenParallelism(5)), "splitTests count(5)");
        SplitStep step = new SplitStep(new TimeDrivenParallelism(3));
        step.setGenerateInclusions(true);
        new SnippetizerTester(jenkinsRule).assertRoundTrip(step, "splitTests generateInclusions: true, parallelism: time(3)");
        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "def splits = splitTests parallelism: count(2), generateInclusions: true\n" +
            "echo \"splits.size=${splits.size()}\"; for (int i = 0; i < splits.size(); i++) {\n" +
            "  def split = splits[i]; echo \"splits[${i}]: includes=${split.includes} list=${split.list}\"\n" +
            "}\n" +
            "node {\n" +
            "  writeFile file: 'TEST-1.xml', text: '<testsuite name=\"one\"><testcase name=\"x\"/></testsuite>'\n" +
            "  writeFile file: 'TEST-2.xml', text: '<testsuite name=\"two\"><testcase name=\"y\"/></testsuite>'\n" +
            "  junit 'TEST-*.xml'\n" +
            "}", true));
        WorkflowRun b1 = jenkinsRule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        jenkinsRule.assertLogContains("splits.size=1", b1);
        jenkinsRule.assertLogContains("splits[0]: includes=false list=[]", b1);
        WorkflowRun b2 = jenkinsRule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        jenkinsRule.assertLogContains("splits.size=2", b2);
        jenkinsRule.assertLogContains("splits[0]: includes=false list=[two.java, two.class]", b2);
        jenkinsRule.assertLogContains("splits[1]: includes=true list=[two.java, two.class]", b2);
    }


    @Test
    public void multiBranchFallbackToPrimaryJob() throws Exception {

        // create a Jenkinsfile in the master branch
        sampleRepo.init();
        String script =
                "def splits = splitTests parallelism: count(2), generateInclusions: true\n" +
                "echo \"branch=${env.BRANCH_NAME}\"\n" +
                        "node {\n" +
                        "  checkout scm\n" +
                        "}";
        sampleRepo.write("Jenkinsfile", script);
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=flow");
        // create a new branch based on master
        sampleRepo.git("branch", "some-branch");
        // checkout a new branch that will get PrimaryInstanceMetadataAction because of it is checked out when indexing
        sampleRepo.git("checkout", "-b", "primary-branch");

        // create MultiBranch project "p"
        WorkflowMultiBranchProject multiBranchProject = jenkinsRule.jenkins.createProject(WorkflowMultiBranchProject.class, "p");
        BranchSource branchSource = new BranchSource(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", false));
        ArrayList<BranchBuildStrategy> buildStrategies = new ArrayList<>();
        buildStrategies.add(new DontBuildBranchBuildStrategy());
        branchSource.setBuildStrategies(buildStrategies);
        multiBranchProject.getSourcesList().add(branchSource);
        // indexing will automatically trigger a run for every branch
        multiBranchProject.scheduleBuild2(0).getFuture().get();
        jenkinsRule.waitUntilNoActivity();
        // MultiBranch project should have 3 items (master, some-branch, primary-branch)
        assertEquals(3, multiBranchProject.getItems().size());

        // trigger primary-branch job again (to make sure that we later pick up the latest build)
        WorkflowJob primaryBranchJob = multiBranchProject.getItem("primary-branch");
        // update the primary job and sneak in some test results
        primaryBranchJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                "  writeFile file: 'TEST-1.xml', text: '<testsuite name=\"one\"><testcase name=\"x\"/></testsuite>'\n" +
                "  writeFile file: 'TEST-2.xml', text: '<testsuite name=\"two\"><testcase name=\"y\"/></testsuite>'\n" +
                "  junit 'TEST-*.xml'\n" +
                "}", true));
        primaryBranchJob.scheduleBuild2(0);
        jenkinsRule.waitUntilNoActivity();
        assertEquals(2, primaryBranchJob.getLastBuild().getNumber());

        // trigger some-branch job again
        WorkflowJob someBranchJob = multiBranchProject.getItem("some-branch");
        someBranchJob.scheduleBuild2(0);
        jenkinsRule.waitUntilNoActivity();
        WorkflowRun someBranchJobBuild2 = someBranchJob.getLastBuild();
        assertEquals(2, someBranchJobBuild2.getNumber());
        jenkinsRule.assertLogContains("Scanning primary project for test records. Starting with build p/primary-branch #2", someBranchJobBuild2);
        // check that we actually pick p/primary-branch#2 because it has test results
        jenkinsRule.assertLogContains("Using build #2 as reference", someBranchJobBuild2);
    }
}
