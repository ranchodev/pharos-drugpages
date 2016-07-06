package ix.idg.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import ix.utils.Global;

import javax.persistence.*;
import java.util.*;
import ix.core.models.Indexable;
import ix.core.models.BeanViews;

@Entity
@Table(name="ix_idg_techdev")
public class Techdev extends play.db.ebean.Model implements java.io.Serializable {
    @Id public Long id;

    // contact
    @Indexable(name="TechDev PI", facet=true)
    public String pi;
    @Indexable(name="TechDev Grant", facet=true)
    public String grant;

    // resource info
    @Lob
    public String comment;
    public String pmcid;
    public Long pmid;

    @Lob
    public String resourceUrl;
    @Lob
    public String dataUrl;

    @ManyToOne(cascade=CascadeType.ALL)
    @JsonView(BeanViews.Full.class)
    public Target target;

    public Techdev () {
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_target")
    public String getJsonTarget () {
        return Global.getRef(target);
    }
}
