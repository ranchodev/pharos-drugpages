package ix.idg.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import ix.utils.Global;

import javax.persistence.*;
import ix.core.models.Indexable;
import ix.core.models.BeanViews;

@Entity
@Table(name = "ix_idg_compartment")
public class Compartment extends play.db.ebean.Model
    implements java.io.Serializable {
    @Id public Long id;
    
    @Indexable(name="Compartment Type", facet=true)
    public String type;
    public String goId;
    @Indexable(facet=true,name="GO Term",suggest=true)
    public String goTerm;
    @Indexable(name="Compartment Evidence",facet=true)
    public String evidence;
    public Double zscore;
    public Double conf;
    public String url;

    public Compartment () {
    }
}
