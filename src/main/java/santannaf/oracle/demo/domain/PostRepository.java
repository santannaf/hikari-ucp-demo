package santannaf.oracle.demo.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends ListCrudRepository<Post, Long> {

    @Query("SELECT ID, TITLE, USER_ID, BODY FROM POSTS WHERE USER_ID = :userId")
    List<Post> findByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(*) FROM POSTS")
    long countAll();
}
