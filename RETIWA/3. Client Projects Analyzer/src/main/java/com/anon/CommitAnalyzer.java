package com.anon;

import com.anon.datatype.CommitInfo;
import com.anon.datatype.RepositoryInfo;

public interface CommitAnalyzer {
    /**
     * Will be called once for each commit
     */
    boolean AnalyzeCommit(RepositoryInfo repo, CommitInfo commit);

    /**
     * Will be called at the very end when all commits are processed.
     */
    void Conclude(RepositoryInfo repo);
}
