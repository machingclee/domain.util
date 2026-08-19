package com.machingclee.domain.util.common.factory;

import com.machingclee.domain.util.common.bytecodescanner.EventTypeScanner;
import com.machingclee.domain.util.common.dto.EntityNodeDTO;
import com.machingclee.domain.util.common.dto.EntityRelationDTO;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityGraphServiceTest {

    @Mock
    ApplicationContext context;
    @Mock
    EntityManager entityManager;
    @Mock
    Metamodel metamodel;
    @Mock
    EntityType<Comment> commentType;

    @Test
    void getEntityNodesKeepsSelfRelations() {
        when(context.getBean("entityManager", EntityManager.class)).thenReturn(entityManager);
        when(entityManager.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(commentType));
        when(commentType.getJavaType()).thenReturn(Comment.class);

        EntityGraphService service = new EntityGraphService(context);
        List<EntityNodeDTO> nodes = service.getEntityNodes();

        String commentName = EventTypeScanner.getReadableClassName(Comment.class);
        assertThat(nodes).extracting(EntityNodeDTO::entityName).containsExactly(commentName);
        List<EntityRelationDTO> relations = nodes.get(0).relations();
        assertThat(relations)
                .extracting(EntityRelationDTO::fieldName)
                .containsExactlyInAnyOrder("parentComment", "childComments");
        assertThat(relations).allSatisfy(rel ->
                assertThat(rel.targetEntity()).isEqualTo(commentName));
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fieldName()).isEqualTo("parentComment");
            assertThat(rel.type()).isEqualTo("MANY_TO_ONE");
        });
        assertThat(relations).anySatisfy(rel -> {
            assertThat(rel.fieldName()).isEqualTo("childComments");
            assertThat(rel.type()).isEqualTo("ONE_TO_MANY");
        });
    }

    /**
     * Mirrors the blog-comment aggregate: parent/child on the same entity via
     * a join table ({@code rel_comment_comment}).
     */
    @Entity
    static class Comment {
        @Id
        UUID id;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinTable(
                name = "rel_comment_comment",
                joinColumns = @JoinColumn(name = "child_comment_id"),
                inverseJoinColumns = @JoinColumn(name = "parent_comment_id"))
        Comment parentComment;

        @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
        @JoinTable(
                name = "rel_comment_comment",
                joinColumns = @JoinColumn(name = "parent_comment_id"),
                inverseJoinColumns = @JoinColumn(name = "child_comment_id"))
        Set<Comment> childComments = new LinkedHashSet<>();
    }
}
