package ix.core.models;

import java.util.Date;
import play.db.ebean.*;
import javax.persistence.*;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.JsonProperty;
import ix.utils.Global;

@Entity
@Table(name="ix_core_processingrecord")
public class ProcessingRecord extends Model {
    public enum Status {
        OK, FAILED, PENDING, UNKNOWN
    }

    @Id
    public Long id;
    public Long start;
    public Long stop;

    @Column(length=128)
    public String name;
    
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

    @OneToOne
    public XRef xref;
    
    @ManyToOne(cascade=CascadeType.ALL)
    @JsonView(BeanViews.Full.class)
    public ProcessingJob job;

    public ProcessingRecord () {}
}
