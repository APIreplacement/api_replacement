# Replication Package for `Don't Reinvent the Wheel: Towards Automatic Replacement of Custom Implementations with APIs`


### `libs.txt`
List of 38 libraries parsed for creating API knowledge based in Fig 1. step 1.

### `data.sqlite`
This file contains the following three tables:
1. `MethodReplacements`: The information of 1033 m->API replacements found in ~10k parsed client projects
2. `MethodReplacements_Filtered`: The 337 replacements that passed the "Replacements Selector" filtering 
3. `LabelingData`: The result of labeling 337 replacements. The `is_valid` column indicates if tagger considered the replacement under inspection (`artifact_id` column) as true positive or not. Each replacement is inspected by two taggers, or three in case of conflicts.

### `Approach`
This folder contain the source code of RETIWA. Please refer to [its README](./RETIWA/README.md) for more details.