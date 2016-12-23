create index concurrently on ix_core_xref(refid);
create index concurrently on ix_core_xref(kind);
create index concurrently on ix_core_xref(refid, kind );

create index concurrently on ix_core_value (label );
create index concurrently on ix_core_value (term );
create index concurrently on ix_core_value (label , term );
create index concurrently on ix_core_value (sha1 );
create index concurrently on ix_core_value (intval );
create index concurrently on ix_core_value (numval );
create index concurrently on ix_core_value (lval );
create index concurrently on ix_core_value (rval );
;

create index concurrently on ix_core_predicate (predicate );
create index concurrently on ix_core_predicate (subject_id , predicate );

create index concurrently on ix_idg_disease (name );

create index concurrently on ix_idg_target (idg_family );
create index concurrently on ix_idg_target (idg_tdl );
create index concurrently on ix_idg_target (novelty );
create index concurrently on ix_idg_target (antibody_count );
create index concurrently on ix_idg_target (monoclonal_count );
create index concurrently on ix_idg_target (pubmed_count );
create index concurrently on ix_idg_target (patent_count );
create index concurrently on ix_idg_target (grant_count );
create index concurrently on ix_idg_target (grant_total_cost );
create index concurrently on ix_idg_target (r01count );
create index concurrently on ix_idg_target (ppi_count);

create index concurrently on ix_idg_harmonogram (uniprot_id );
create index concurrently on ix_idg_harmonogram (symbol );
create index concurrently on ix_idg_harmonogram (data_source );
create index concurrently on ix_idg_harmonogram (data_type );
create index concurrently on ix_idg_harmonogram (attr_group );
create index concurrently on ix_idg_harmonogram (attr_type );
create index concurrently on ix_idg_harmonogram (idgfamily );
create index concurrently on ix_idg_harmonogram (tdl );
create index concurrently on ix_idg_harmonogram (cdf );

create index concurrently on ix_idg_tinx (uniprot_id );
create index concurrently on ix_idg_tinx (doid );
create index concurrently on ix_idg_tinx (importance );
create index concurrently on ix_idg_tinx (disease_novelty );

create index concurrently on ix_idg_compartment (type );
create index concurrently on ix_idg_compartment (go_id );
create index concurrently on ix_idg_compartment (go_term );
create index concurrently on ix_idg_compartment (evidence );

create index concurrently on ix_idg_target_link (ix_idg_target_id );

create index concurrently on ix_idg_target_property (ix_idg_target_id );

create index concurrently on ix_idg_target_publication (ix_idg_target_id );

create index concurrently on ix_idg_target_synonym (ix_idg_target_synonym_id );
