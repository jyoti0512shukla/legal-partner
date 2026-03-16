CREATE TABLE query_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id VARCHAR(255),
    username VARCHAR(255) NOT NULL,
    query_text TEXT NOT NULL,
    answer_text TEXT,
    rating INTEGER CHECK (rating BETWEEN 1 AND 5),
    is_correct BOOLEAN,
    corrected_answer TEXT,
    feedback_note TEXT,
    matter_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_query_feedback_username ON query_feedback(username);
CREATE INDEX idx_query_feedback_rating ON query_feedback(rating);
CREATE INDEX idx_query_feedback_conversation ON query_feedback(conversation_id);
CREATE INDEX idx_query_feedback_created ON query_feedback(created_at);
