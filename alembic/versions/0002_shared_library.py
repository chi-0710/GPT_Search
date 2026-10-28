"""Shared document identities and durable research evidence."""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0002_shared_library"
down_revision = "0001_postgres_persistence"
branch_labels = None
depends_on = None


def upgrade():
    op.drop_constraint("documents_filename_key", "documents", type_="unique")
    op.create_unique_constraint("uq_documents_filename_sha256", "documents", ["filename", "sha256"])
    op.create_table(
        "research_evidence",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("research_runs.id", ondelete="CASCADE"), nullable=False),
        sa.Column("number", sa.Integer(), nullable=False),
        sa.Column("snapshot", postgresql.JSONB(), nullable=False),
        sa.UniqueConstraint("run_id", "number", name="uq_evidence_run_number"),
    )


def downgrade():
    # Refuse to discard or rename coexisting documents during a rollback.
    bind = op.get_bind()
    if bind.scalar(sa.text("SELECT EXISTS (SELECT 1 FROM documents GROUP BY filename HAVING count(*) > 1)")):
        raise RuntimeError("Cannot downgrade while different documents share a filename")
    op.drop_table("research_evidence")
    op.drop_constraint("uq_documents_filename_sha256", "documents", type_="unique")
    op.create_unique_constraint("documents_filename_key", "documents", ["filename"])
