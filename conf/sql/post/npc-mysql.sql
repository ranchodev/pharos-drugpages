ALTER TABLE ix_core_xref
ADD INDEX xref_refid_index (refid ASC),
ADD INDEX xref_kind_index (kind ASC),
add index refid_kind_index (refid asc, kind asc);

ALTER TABLE ix_core_value
ADD INDEX value_label_index (label ASC),
ADD INDEX value_term_index (term ASC),
add index label_term_index (label asc, term asc),
add index sha1_index (sha1 asc),
add index intval_index (intval asc),
add index numval_index (numval asc),
add index lval_index (lval asc),
add index rval_index (rval asc)
;

ALTER TABLE ix_core_predicate
ADD INDEX predicate_index (predicate ASC),
add index subject_pred_index (subject_id asc, predicate asc)
;

ALTER TABLE ix_core_job
ADD INDEX `job_version_index` (`version` ASC);
;

ALTER TABLE ix_core_record
ADD INDEX `record_version_index` (`version` ASC);
;

ALTER TABLE ix_core_structure
ADD INDEX `structure_version_index` (`version` ASC);
;

ALTER TABLE ix_npc_entity
ADD INDEX `npc_name_index` (`name` ASC),
ADD INDEX `npc_version_index` (`version` ASC),
ADD INDEX `npc_type_index` (`type` ASC);
;

