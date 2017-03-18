package ix.core.models;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import play.db.ebean.*;

import javax.persistence.*;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonProperty;

import ix.utils.Global;

@Entity
@Table(name="ix_core_record")
public class Record extends Model {
    public enum Status {
        OK, FAILED, PENDING, UNKNOWN, ADAPTED
    }

    @Id public Long id;
    @Version public Long version;

    @Column(length=255)
    public String name;
    
    @ManyToMany
    @JoinTable(name="ix_core_record_prop")
    public List<Value> properties = new ArrayList<Value>();
    
    /**
     * record status
     */
    public Status status = Status.PENDING;

    /**
     * detailed status message
     */
    @Lob
    @Basic(fetch=FetchType.EAGER)
    public String message;

    @OneToOne(cascade=CascadeType.ALL)
    public XRef xref;
    
    @ManyToOne(cascade=CascadeType.ALL)
    @JsonView(BeanViews.Full.class)
    public Job job;

    public final Timestamp created =
        new Timestamp (System.currentTimeMillis());
    
    public Record () {}
}
