package ix.core.models;

import play.db.ebean.Model;
import javax.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@DiscriminatorValue("KEY")
@DynamicFacet(label="label", value="term")
public class Keyword extends Value implements Comparable<Keyword> {
    @Column(length=255)
    public String term;
    @Lob
    @Basic(fetch=FetchType.EAGER)
    public String href;

    public Keyword () {}
    public Keyword (String term) {
        this.term = term;
    }
    public Keyword (String label, String term) {
        super (label);
        this.term = term;
    }

    @Override
    public String getValue () { return term; }

    public boolean equals (Object obj) {
        if (obj instanceof Keyword) {
            Keyword kw = (Keyword)obj;
            if (label != null && term != null)
                return label.equals(kw.label) && term.equals(kw.term);
        }
        return false;
    }

    public int compareTo (Keyword kw) {
        int d = label.compareTo(kw.label);
        if (d == 0)
            d = term.compareTo(kw.term);
        return d;
    }
}
