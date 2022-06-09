package com.anon.helpers;

import org.apache.commons.collections.map.HashedMap;

import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InterestingCommitsLoader {

    static private Map<String, Set<String>> interestingCommits = new HashedMap();

    public static void LoadInterestingCommits(Path DB_PATH) {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            Statement stmt = conn.createStatement();

            ResultSet rs = stmt.executeQuery("SELECT repo_name, cur_commit, prev_commit\n" +
                                                    "FROM APIReplacements\n" +
                                                    "GROUP BY repo_name, cur_commit\n" +
                                                    "ORDER BY repo_name");

            while(rs.next()) {
                String repoName = rs.getString(1);
                String curCommit = rs.getString(2);
//                String parCommit = rs.getString(3);

                if(!interestingCommits.containsKey(repoName))
                    interestingCommits.put(repoName, new HashSet<>());
                interestingCommits.get(repoName).add(curCommit);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static Map<String, Set<String>> getInterestingCommits() {
        return interestingCommits;
    }
}
