package com.productrag.rag;

import com.productrag.common.exception.BadRequestException;
import com.productrag.config.AppProperties;
import java.text.Normalizer;
import org.springframework.stereotype.Component;

/** Validates and normalizes user questions before they reach retrieval or the LLM. */
@Component
public class QuestionNormalizer {

    private final int maxLength;

    public QuestionNormalizer(AppProperties properties) {
        this.maxLength = properties.rag().maxQuestionLength();
    }

    public String normalize(String question) {
        if (question == null) {
            throw new BadRequestException("INVALID_QUESTION", "Question is required");
        }
        String text = Normalizer.normalize(question, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isEmpty()) {
            throw new BadRequestException("INVALID_QUESTION", "Question must not be empty");
        }
        if (text.length() > maxLength) {
            throw new BadRequestException("INVALID_QUESTION",
                    "Question must be at most " + maxLength + " characters");
        }
        return text;
    }
}
