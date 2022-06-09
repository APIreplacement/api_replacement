# RETIWA

1. [**Libraries Miner**](./1.%20Libraries%20Miner/): This srcipt downloads `jar` files for a given Maven group id (for our paper, `org.apache.commons`). See II.A in the paper.

2. [**API Extractor**](./2.%20API%20Extractor/): Given a Java repository folder, this project extracts all method invocations and method declarations into a database file. See II.A in the paper.

3. [**Client Projects Analyzer**](3.%20Client%20Projects%20Analyzer/): This project is the main part of the RETIWA workflow. See II.B in the paper.

4. **Replacements Selector**: Given the result of the previous steps (candidate replacements), as described in paper in section II.C, we exclude those matching the following criteria: (i) the custom method is either a getter, a setter, or a `main` method, and (ii) the number of replacements performed in a commit is less than a certain threshold.
