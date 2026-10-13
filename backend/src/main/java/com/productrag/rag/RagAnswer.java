package com.productrag.rag;

import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.rag.dto.SourceReference;
import java.util.List;

public record RagAnswer(String answer, List<SourceReference> sources, RetrievalInfo retrieval) {
}
