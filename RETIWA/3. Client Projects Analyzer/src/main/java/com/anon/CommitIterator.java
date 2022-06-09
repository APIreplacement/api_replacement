package com.anon;

import com.anon.datatype.CommitInfo;
import com.anon.datatype.RepositoryInfo;
import com.anon.helpers.IO;
import com.anon.helpers.InterestingCommitsLoader;
import com.anon.cmdrunners.GitCmdRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CommitIterator {
    private static final Logger logger = LoggerFactory.getLogger(CommitIterator.class);

    private final RepositoryInfo repo;

    public CommitIterator(RepositoryInfo _repo) {
        repo = _repo;
    }

    public void StartIteratingCommits(CommitAnalyzer ca) {
        if (!SetRepositoryFree()) {
            return;
        }

        List<CommitInfo> allCommits = GitCmdRunner.GetListOfCommits_withMessage_FirstParent(this.repo, true, true);
        if(allCommits==null) {
            logger.error("({}/{}) {} Failed to retrieve list of commits!", Main.totalReposProcessed.get(), Main.totalRepos, repo);
            return;
        }
        Collections.reverse(allCommits);


        //allCommits = CherryPickCommitsBasedOnPreviousResults(repo.GetFullName(), allCommits); //TODO: Commented temporarily

        WriteCommitsToFile(allCommits);

//        allCommits.set(0, new CommitInfo(EMPTY_TREE_SHA,"---"));
//        boolean dontskip = false;
        for (int cIndex = 1; cIndex < allCommits.size(); cIndex++) {
//            if(allCommits.get(cIndex+1).commitSHA.startsWith("35bc87526def88a75230b863376e5c5827c7b205"))
//                dontskip=true;
//            if(!dontskip)
//                continue;

            CommitInfo curCommitBasicInfo = allCommits.get(cIndex);
            String prevCommitSHA = allCommits.get(cIndex - 1).commitSHA;
            CommitInfo commit = new CommitInfo(curCommitBasicInfo.commitSHA, prevCommitSHA, cIndex, allCommits.size(), curCommitBasicInfo.commitMessage_subject);

            try {
                logger.info("({}/{}) {} Analyzing Commit {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
                boolean res = ca.AnalyzeCommit(this.repo, commit);
                if(!res)
                    logger.error("({}/{}) {} Analyzing Commit {} FAILED ???", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
            } catch (Exception e) {
                logger.error("({}/{}) {} Analyzing Commit {} FAILED", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, e);
            }
        } // end of for loop

        try {
            ca.Conclude(this.repo);
        } catch (Exception e) {
            logger.error("({}/{}) {} Analyzing All Commit Finished. Writing results FAILED", Main.totalReposProcessed.get(), Main.totalRepos, repo, e);
        }
    }

    private List<CommitInfo> CherryPickCommitsBasedOnPreviousResults(String repo, List<CommitInfo> allCommits) {
        List<CommitInfo> commitsInvolvedInPreviousResultsAndTheirParent = new ArrayList<>();
        Set<Integer> selectedCommitIndices = new HashSet<>();

        commitsInvolvedInPreviousResultsAndTheirParent.add(allCommits.get(0));
        selectedCommitIndices.add(0);

//        InterestingCommitsLoader.interestingCommits.get("butterproject/butter-android").clear();
//        InterestingCommitsLoader.interestingCommits.get("butterproject/butter-android").add("e10a1b2c409bd8b52f5b6e5debf7323b585881bd");


        for(int i=1; i<allCommits.size(); i++) {
            CommitInfo c = allCommits.get(i);
            if (InterestingCommitsLoader.getInterestingCommits().get(repo).contains(c.commitSHA)) {
                if(!selectedCommitIndices.contains(i-1))
                {
                    // Adding parent commit (Why? we want to fast-forward uninteresting commits, but to get a meaningful diff output, we need to stop on the commit before those with interesting cases)
                    commitsInvolvedInPreviousResultsAndTheirParent.add(allCommits.get(i-1));
                    selectedCommitIndices.add(i-1);
                }
                // Adding actual commit
                commitsInvolvedInPreviousResultsAndTheirParent.add(c);
                selectedCommitIndices.add(i);
            }
        }
        return commitsInvolvedInPreviousResultsAndTheirParent;
    }

    private void WriteCommitsToFile(List<CommitInfo> allCommits) {
        List<String> allCommitsSHA = new ArrayList<>();
        for(CommitInfo c: allCommits)
            allCommitsSHA.add(c.commitSHA);
        IO.WriteCSV_SingleColumn(allCommitsSHA,
                this.repo.GetPath().getParent().resolve(this.repo.GetFullName().split("/")[1] + "_commits.csv"));
    }


    /**
     * Check if `.git/index.lock` exists, and will delete it
     *
     * @return Returns `false` if file exists and can not be deleted; Returns `true` otherwise.
     */
    private boolean SetRepositoryFree() {
        Path gitLockFile = repo.GetPath().resolve(".git").resolve("index.lock");
        if (Files.exists(gitLockFile)) {
            boolean res = gitLockFile.toFile().delete();
            if (res) {
                logger.info("{} git-lock deleted", repo.GetFullName());
                return true;
            } else {
                logger.error("{} git-lock deletion FAILED", repo.GetFullName());
                return false;
            }
        }
        return true;
    }
}
