package ix.core.models;

import ix.core.stats.Statistics;
import ix.utils.Global;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.persistence.*;

import play.Logger;
import play.db.ebean.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

@Entity
@Table(name="ix_core_job")
public class Job extends Model {
        
    public enum Status {
        COMPLETE, RUNNING, NOT_RUN, FAILED, PENDING, STOPPED, UNKNOWN
    }
    
    @Id public Long id;
    @Version public Long version;
    
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name="ix_core_job_key")
    public List<Keyword> keys = new ArrayList<Keyword>();

    @Indexable(facet=true, name="Job Status")
    public Status status = Status.PENDING;

    @Lob
    @Basic(fetch=FetchType.EAGER)
    public String message;

    @OneToOne(cascade=CascadeType.ALL)
    @JsonView(BeanViews.Full.class)
    public Principal owner;
    
    @OneToOne(cascade=CascadeType.ALL)
    @JsonView(BeanViews.Full.class)
    public Payload payload;

    public final Timestamp created =
        new Timestamp (System.currentTimeMillis());
    public Timestamp lastUpdate;

    public Integer processed;
    public Integer failed;

    public Job () {
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_payload")
    public String getJsonPayload () {
        return payload != null
            ? Global.getRef(getClass (), id)+"/payload" : null;
    }

    @JsonView(BeanViews.Compact.class)
    @JsonProperty("_owner")
    public String getJsonOwner () {
        return owner != null
            ? Global.getRef(getClass (), id)+"/owner" : null;
    }
    
    public String getKeyMatching(String label){
        for(Keyword k : keys){
            if(label.equals(k.label)){
                return k.getValue();
            }
        }
        return null;
    }
    public boolean hasKey(String term){
        for(Keyword k : keys){
            if(term.equals(k.term)){
                return true;
            }
        }
        return false;
    }

    @PrePersist
    @PreUpdate
    public void modified () {
        lastUpdate = new Timestamp (System.currentTimeMillis());
    }
}
