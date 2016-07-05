package ix.idg.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import ix.core.models.*;
import ix.utils.Global;

import javax.persistence.*;
import java.util.*;
import ix.core.models.Indexable;

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

    @ManyToOne(cascade=CascadeType.ALL)
    @JsonView(BeanViews.Full.class)
    public Target target;

    public Compartment () {
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_target")
    public String getJsonTarget () {
        return Global.getRef(target);
    }
}
