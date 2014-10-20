package ix.core.models;

import play.db.ebean.Model;
import javax.persistence.*;

@Entity
@Table(name="ix_core_pubauthor")
public class PubAuthor extends Model {
    @Id
    public Long id;

    public int position;
    public boolean isLast;
    // corresponding author?
    public Boolean correspondence;

    @OneToOne(cascade=CascadeType.ALL)
    public Author author;

    public PubAuthor () {}
    public PubAuthor (int position, Author author) {
        this (position, false, author);
    }
    public PubAuthor (int position, boolean isLast, Author author) {
        this.position = position;
        this.isLast = isLast;
        this.author = author;
    }
}
