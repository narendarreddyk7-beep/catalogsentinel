-- =====================================================================================================
-- V5 — Match evidence currency (docs/adr/0013-match-evidence-currency.md).
--
-- A match candidate is evidence about the two listings' content at the time it was scored (left/right
-- content hashes). When either listing changes or is withdrawn, matching now marks every candidate built on
-- the old content SUPERSEDED, including human APPROVED/REJECTED decisions. Superseding a human decision is
-- recorded in the append-only audit trail as a system decision, so this migration adds that vocabulary.
-- DDL is not affected by the trail's row-level append-only triggers; existing rows are re-validated.
-- =====================================================================================================

ALTER TABLE review_decisions DROP CONSTRAINT ck_review_decisions_decision;
ALTER TABLE review_decisions ADD CONSTRAINT ck_review_decisions_decision CHECK (decision IN
    ('APPROVE', 'REJECT', 'CONFIRM', 'DISMISS', 'RESOLVE', 'REOPEN', 'SUPERSEDE'));
