ALTER TABLE ix_core_xref
ADD INDEX xref_refid_index (refid ASC),
ADD INDEX xref_kind_index (kind ASC);

ALTER TABLE ix_core_value
ADD INDEX value_label_index (label ASC),
ADD INDEX value_term_index (term ASC);


ALTER TABLE ix_ginas_substance
ADD INDEX sub_approval_index (approval_id ASC);

ALTER TABLE ix_ginas_name
ADD INDEX name_index (name ASC);

ALTER TABLE ix_ginas_reference
ADD INDEX ref_id_index (id ASC);


ALTER TABLE ix_ginas_relationship
ADD INDEX interaction_index (interaction_type ASC),
ADD INDEX qualification_index (qualification ASC),
ADD INDEX type_index (type ASC);
