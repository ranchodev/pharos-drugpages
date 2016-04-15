This file describes the column headings in the CSV files obtained when downloading targets, diseases or ligands. Each
section below corresponds to individual entity files.

diseases.csv
------------

This file describes the diseases associated with a target. Each row corresponds to one disease

Column Headers -

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
DOID - the Disease Ontology identifier for this disease
Name - name of the disease
Description - description of the disease
ZScore - the Jensen Lab text mining Z-score (http://www.sciencedirect.com/science/article/pii/S1046202314003831)
Confidence - the Jensen Lab text mining confidence (http://www.sciencedirect.com/science/article/pii/S1046202314003831)
Link - link to the entry for this disease/target association in the DISEASES database

expression.csv
--------------

This file lists the tissue expression levels associated with a target. Each line is a single tissue expression level
for a single target.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
Source - source of the expression data
Tissue - the name of the tissue
NumericValue - the numeric expression level
QualitativeValue - the qualitative expression level
Confidence - confidence in the expression value
Evidence - evidence for the expression value

generifs.csv
------------

This file lists the Gene RIFs (http://www.ncbi.nlm.nih.gov/gene/about-generif) associated with a target. Each line
is a single target - Gene RIF association.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
PMID - Pubmed ID for the article from which the Gene RIF was obtained
Abstract - the abstract of the article

goterms.csv
-----------

This file lists the GO (http://geneontology.org/) terms associated with a target. Each line is a single target -
GO term association.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
GOTerm - the term name
GOType - the GO term type (process, function or cellular location)

ligands.csv
-----------

This file lists the ligands associated with a target. Each line is a single target-ligand association.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
Name - ligand name
Type - ligand type
Description - ligand description
SMILES - the SMILES representation of the ligand structure
Link -
ChEMBL Activity - numeric activity value from ChEMBL
ChEMBL Activity Type - text string indicating the type of activity (e.g., Ki)

pathways.csv
------------

This file lists pathways associated with a target. Each line is a single target-pathway association.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
Name - pathway name
Source - source database for the pathway
Link - link to this pathway in the source database

publications.csv
----------------

This file lists publications associated with a target, obtained via text mining. Each line is a target-publication
association.


URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
PMID - Pubmed ID for the article from which the Gene RIF was obtained
Title - article title
Abstract - the abstract of the article

targets.csv
-----------

This file lists information associated with a target. Each line is a single target.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
Name - name of target from Uniprot
Description - description from Uniprot
Development Level - target development level (e.g., Tdark)
DTOClass - Drug Target Ontology assigned to the target
PantherClass - Panther Ontology assigned to the target
ChemblClass - target class from ChEMBL
Novelty - novelty score
Target Family - target family (e.g., kinase, GPCR)
Function -
GrantCount - number of grants referring to this target
R01Count - number of R01's referring to this target
PatentCount - number of patents referring to this target
AntibodyCount - number of antibodies available for this target
PubmedCount - number of articles associated with the target (obtained via text mining)
PMIDS - comma separated list of Pubmed IDs for the associated articles

uniprot-keywords.csv
--------------------

This file lists Uniprot keywords associated with a target. Each line is a single target-keyword association.

URL - the partial URL to the disease entry on the Pharos website. You should prepend it with the host name (e.g.,
https://pharos.nih.gov/) to get the complete URL.
Uniprot ID - the Uniprot ID of the target associated with this disease
Keyword - the Uniprot keyword
Link - link to the keyword on Uniprot