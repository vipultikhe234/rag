package com.productrag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.productrag.rag.CatalogOverview.Subject;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class CatalogOverviewTest {

    @Test
    void catalogQuestionsAreDetectedWhateverTheWording() {
        assertThat(CatalogOverview.detect("how much brand we have")).containsExactly(Subject.BRANDS);
        assertThat(CatalogOverview.detect("How many brands are there?")).containsExactly(Subject.BRANDS);
        assertThat(CatalogOverview.detect("list all categories and subcategories"))
                .containsExactly(Subject.CATEGORIES);
        assertThat(CatalogOverview.detect("total brands and categories"))
                .containsExactly(Subject.BRANDS, Subject.CATEGORIES);
    }

    @Test
    void productQuestionsThatMentionASubjectWordAreNotCatalogQuestions() {
        assertThat(CatalogOverview.detect("which brands make gaming laptops")).isEmpty();
        assertThat(CatalogOverview.detect("Nike shoes under 3000")).isEmpty();
        assertThat(CatalogOverview.detect("how many products")).isEmpty();
        assertThat(CatalogOverview.detect("")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void brandAnswerCountsEveryBrandInTheTableNotJustRetrievedProducts() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            rows.add(new String[] {"Brand " + i, "ACTIVE", String.valueOf(i % 3)});
        }
        rows.add(new String[] {"Old_Brand", "INACTIVE", "0"});
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (String[] row : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("name")).thenReturn(row[0]);
                when(rs.getString("status")).thenReturn(row[1]);
                when(rs.getLong("products")).thenReturn(Long.parseLong(row[2]));
                mapped.add(mapper.mapRow(rs, mapped.size()));
            }
            return mapped;
        });

        RetrievalResult result = new CatalogOverview(jdbc).answer("how much brand we have").orElseThrow();

        assertThat(result.strategy()).isEqualTo(RetrievalStrategy.CATALOG);
        assertThat(result.totalMatches()).isEqualTo(41L);
        assertThat(result.message()).contains("**41 brands**", "40 active and 1 inactive", "Brand 40",
                "Old\\_Brand");
    }
}
