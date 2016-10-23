package ix.idg.controllers;

import ix.core.models.EntityModel;

public interface Descriptor<T extends EntityModel> {
    String name ();
    Number value (T entity) throws Exception;
}
