package ix.idg.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import ix.core.models.*;
import ix.utils.Global;

import javax.persistence.*;
import java.util.*;

@Entity
@Table(name="ix_idg_assay")
public class Assay extends EntityModel {
    /*
     * there has to be a way around this cut and paste boilerplate code !!!
     */
    @Indexable(name="Assay Type", facet=true)
    public String type;
    
    @Column(length=1024)
    @Indexable(suggest=true,name="Assay")
    public String name;

    @Lob
    @Basic(fetch=FetchType.EAGER)
    public String description;
    
    @JsonView(BeanViews.Full.class)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_idg_assay_synonym",
               joinColumns=@JoinColumn(name="ix_idg_assay_synonym_id",
                                       referencedColumnName="id")
               )
    public List<Keyword> synonyms = new ArrayList<Keyword>();

    @JsonView(BeanViews.Full.class)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_idg_assay_property")
    public List<Value> properties = new ArrayList<Value>();

    @JsonView(BeanViews.Full.class)
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_idg_assay_link")
    public List<XRef> links = new ArrayList<XRef>();

    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_idg_assay_publication")
    @JsonView(BeanViews.Full.class)
    public List<Publication> publications = new ArrayList<Publication>();

    public Assay () {}
    public Assay (String name) { this.name = name; }
    public String getName () { return name; }
    public String getDescription () { return description; }
    public List<Keyword> getSynonyms () { return synonyms; }
    public List<Value> getProperties () { return properties; }
    public List<XRef> getLinks () { return links; }
    public List<Publication> getPublications () { return publications; }
    // this is necessary because we can't use assay.type in swirl.. sigh
    public String getType () { return type; }
}
