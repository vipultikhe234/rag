package com.productrag.rag;

/** A previous message of the conversation, passed to the LLM for follow-up questions. */
public record HistoryTurn(String role, String content) {
}
