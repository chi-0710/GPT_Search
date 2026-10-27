CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS alembic_version (version_num varchar(32) PRIMARY KEY);
INSERT INTO alembic_version VALUES ('0002_shared_library') ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS reports (
  id uuid PRIMARY KEY, public_id text UNIQUE, question text, answer text,
  ordered_data jsonb, status text, updated_at timestamptz
);
CREATE TABLE IF NOT EXISTS chat_messages (
  id uuid PRIMARY KEY, report_id uuid REFERENCES reports(id), sequence bigint,
  role text, content text, metadata jsonb, client_timestamp bigint, created_at timestamptz
);
CREATE TABLE IF NOT EXISTS documents (
  id uuid PRIMARY KEY, filename text, mime_type text, byte_size bigint, sha256 text,
  status text, error text, created_at timestamptz, updated_at timestamptz,
  embedding_profile_id uuid, content bytea
);
INSERT INTO reports VALUES
 ('00000000-0000-0000-0000-000000000001','old-report','问题','正文','[]','completed','2026-01-01T00:00:00.123Z'),
 ('00000000-0000-0000-0000-000000000002','draft','问题','','[]','running','2026-01-02T00:00:00Z')
 ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS research_runs (
  id uuid PRIMARY KEY, report_id uuid REFERENCES reports(id), query text, report_type text,
  report_source text, tone text, status text, error text, cost_summary jsonb, request_options jsonb,
  started_at timestamptz, completed_at timestamptz, created_at timestamptz
);
CREATE TABLE IF NOT EXISTS research_events (
  id bigint PRIMARY KEY, run_id uuid REFERENCES research_runs(id), payload jsonb, created_at timestamptz
);
CREATE TABLE IF NOT EXISTS research_evidence (
  id bigint PRIMARY KEY, run_id uuid REFERENCES research_runs(id), number integer, snapshot jsonb
);
CREATE TABLE IF NOT EXISTS research_sources (
  id uuid PRIMARY KEY, run_id uuid REFERENCES research_runs(id), url text, title text, raw_content text,
  source_type text, metadata jsonb, created_at timestamptz
);
CREATE TABLE IF NOT EXISTS artifacts (
  id uuid PRIMARY KEY, report_id uuid REFERENCES reports(id), filename text, mime_type text,
  sha256 text, content bytea, kind text, legacy_path text, created_at timestamptz
);
INSERT INTO research_runs VALUES
 ('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-000000000001','问题','deep',
  'hybrid','Objective','completed',null,'{}','{}','2026-01-01T00:00:00Z',null,'2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO research_events VALUES
 (1,'00000000-0000-0000-0000-000000000101','{"id":999,"type":"logs","createdAt":"spoof"}','2026-01-01T00:00:00Z')
 ON CONFLICT DO NOTHING;
INSERT INTO research_evidence VALUES
 (1,'00000000-0000-0000-0000-000000000101',1,'{"number":1,"source_type":"local","document_id":"00000000-0000-0000-0000-000000000021","content":"保留原文"}'),
 (2,'00000000-0000-0000-0000-000000000101',2,'{"number":2,"source_type":"local","document_id":"deleted","content":"删除后仍保留快照"}'),
 (3,'00000000-0000-0000-0000-000000000101',3,'{"number":3,"source_type":"web","url":"https://example.com"}')
 ON CONFLICT DO NOTHING;
INSERT INTO research_sources VALUES
 ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000101',
  'https://example.com','来源','正文','web','{}','2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO artifacts VALUES
 ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000001',
  '报告.docx','application/octet-stream','hash',decode('00010203','hex'),'docx','outputs/report.docx','2026-01-01T00:00:00Z')
 ON CONFLICT DO NOTHING;
INSERT INTO chat_messages VALUES
 ('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000001',2,'assistant','后回答','{"source":"report"}',0,'2026-01-01T00:00:00Z'),
 ('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000001',1,'user','先提问',null,100,'2026-01-01T00:00:00Z')
 ON CONFLICT DO NOTHING;
INSERT INTO chat_messages VALUES
 ('00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000001',3,'user','JSON null 元数据','null'::jsonb,200,'2026-01-01T00:00:00Z')
 ON CONFLICT DO NOTHING;
INSERT INTO documents VALUES
 ('00000000-0000-0000-0000-000000000021','资料.pdf','application/pdf',4,'hash','ready',null,
  '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',null,'test'::bytea)
 ON CONFLICT DO NOTHING;
