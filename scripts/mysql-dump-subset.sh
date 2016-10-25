#!/bin/sh

tcrd="tcrd307"
p_cond="(select protein_id from techdev_info)"
t_cond="(select target_id from t2tc a, techdev_info b where a.protein_id = b.protein_id)"

out="${tcrd}_01.sql"
echo "Dumping database $tcrd to $out..."
# first dump all small tables
mysqldump -uroot $tcrd \
    alias \
    dataset \
    data_type \
    dbinfo \
    disease_association_type \
    do \
    do_parent \
    dto \
    expression_type \
    gene_attribute_type \
    info_type \
    mlp_assay_info \
    p2pc \
    panther_class \
    patent_count \
    pathway_type \
    phenotype_type \
    ppi \
    ppi_type \
    techdev_contact \
    techdev_info \
    tinx_disease \
    xref_type > $out

out="${tcrd}_02.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd chembl_activity > $out

out="${tcrd}_03.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd compartment > $out

out="${tcrd}_04.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd drug_activity > $out

out="${tcrd}_05.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd expression > $out

out="${tcrd}_06.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd feature > $out

out="${tcrd}_07.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd gene_attribute > $out

out="${tcrd}_08.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd generif > $out

out="${tcrd}_09.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd goa > $out

out="${tcrd}_10.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd hgram_cdf > $out

out="${tcrd}_11.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd pathway > $out

out="${tcrd}_12.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd phenotype > $out

out="${tcrd}_13.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd pmscore > $out

out="${tcrd}_14.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "id in $p_cond" \
          $tcrd protein > $out

out="${tcrd}_15.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd protein2pubmed > $out

out="${tcrd}_16.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables \
    -w "id in (select pubmed_id from protein2pubmed where protein_id in $p_cond)" \
    $tcrd pubmed > $out

out="${tcrd}_17.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd t2tc > $out

out="${tcrd}_18.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "id in $t_cond" \
          $tcrd target > $out

out="${tcrd}_19.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd target2disease > $out

out="${tcrd}_20.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd target2grant > $out

out="${tcrd}_21.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd tdl_info > $out

out="${tcrd}_22.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd tinx_importance > $out

out="${tcrd}_23.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "protein_id in $p_cond" \
          $tcrd tinx_novelty > $out

out="${tcrd}_24.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd xref > $out

out="${tcrd}_25.sql"
echo "Dumping $out..."
mysqldump -uroot --lock-all-tables -w "target_id in $t_cond" \
          $tcrd pathway > $out

echo "ALL DONE!!!"
