CREATE TABLE pending_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    type VARCHAR(20) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    description TEXT,
    transaction_date DATE NOT NULL,
    suggested_category VARCHAR(100),
    dedup_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pending_document_dedup UNIQUE (document_id, dedup_hash)
);
CREATE INDEX idx_pending_transactions_document_id ON pending_transactions(document_id);
CREATE INDEX idx_pending_transactions_user_id ON pending_transactions(user_id);
