package santannaf.oracle.demo.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("POSTS")
public record Post(
        @Id Long id,
        @Column("TITLE") String title,
        @Column("USER_ID") Long userId,
        @Column("BODY") String body
) {
    public static Post create(String title, Long userId, String body) {
        return new Post(null, title, userId, body);
    }
}
