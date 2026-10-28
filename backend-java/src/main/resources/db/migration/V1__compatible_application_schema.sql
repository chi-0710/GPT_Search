




CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;








CREATE TABLE public.alembic_version (
    version_num character varying(32) NOT NULL
);



CREATE TABLE public.artifacts (
    id uuid NOT NULL,
    report_id uuid,
    run_id uuid,
    kind character varying(40) NOT NULL,
    filename character varying(255) NOT NULL,
    mime_type character varying(160) NOT NULL,
    byte_size bigint NOT NULL,
    sha256 character varying(64) NOT NULL,
    content bytea NOT NULL,
    legacy_path text,
    metadata jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_artifacts_byte_size CHECK ((byte_size >= 0))
);



CREATE TABLE public.chat_messages (
    id uuid NOT NULL,
    report_id uuid NOT NULL,
    sequence bigint NOT NULL,
    role character varying(16) NOT NULL,
    content text NOT NULL,
    metadata jsonb,
    client_timestamp bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_chat_messages_role CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying, 'system'::character varying])::text[])))
);



CREATE TABLE public.document_chunks (
    id bigint NOT NULL,
    document_id uuid NOT NULL,
    embedding_profile_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    metadata jsonb NOT NULL,
    embedding public.vector NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



CREATE SEQUENCE public.document_chunks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;



ALTER SEQUENCE public.document_chunks_id_seq OWNED BY public.document_chunks.id;



CREATE TABLE public.documents (
    id uuid NOT NULL,
    filename character varying(255) NOT NULL,
    mime_type character varying(160) NOT NULL,
    byte_size bigint NOT NULL,
    sha256 character varying(64) NOT NULL,
    content bytea NOT NULL,
    status character varying(16) NOT NULL,
    error text,
    embedding_profile_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_documents_byte_size CHECK ((byte_size >= 0)),
    CONSTRAINT ck_documents_status CHECK (((status)::text = ANY ((ARRAY['processing'::character varying, 'ready'::character varying, 'failed'::character varying])::text[])))
);



CREATE TABLE public.embedding_profiles (
    id uuid NOT NULL,
    provider character varying(80) NOT NULL,
    model character varying(255) NOT NULL,
    dimensions integer NOT NULL,
    active boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



CREATE TABLE public.migration_imports (
    id uuid NOT NULL,
    source_path text NOT NULL,
    sha256 character varying(64) NOT NULL,
    status character varying(20) NOT NULL,
    error text,
    details jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



CREATE TABLE public.reports (
    id uuid NOT NULL,
    public_id character varying(160) NOT NULL,
    question text NOT NULL,
    answer text NOT NULL,
    ordered_data jsonb NOT NULL,
    status character varying(16) NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_reports_status CHECK (((status)::text = ANY ((ARRAY['queued'::character varying, 'running'::character varying, 'completed'::character varying, 'failed'::character varying])::text[])))
);



CREATE TABLE public.research_events (
    id bigint NOT NULL,
    run_id uuid NOT NULL,
    event_type character varying(80) NOT NULL,
    content_key character varying(160),
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



CREATE SEQUENCE public.research_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;



ALTER SEQUENCE public.research_events_id_seq OWNED BY public.research_events.id;



CREATE TABLE public.research_evidence (
    id bigint NOT NULL,
    run_id uuid NOT NULL,
    number integer NOT NULL,
    snapshot jsonb NOT NULL
);



CREATE SEQUENCE public.research_evidence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;



ALTER SEQUENCE public.research_evidence_id_seq OWNED BY public.research_evidence.id;



CREATE TABLE public.research_runs (
    id uuid NOT NULL,
    report_id uuid,
    query text NOT NULL,
    report_type character varying(80) NOT NULL,
    report_source character varying(80) NOT NULL,
    tone character varying(80) NOT NULL,
    request_options jsonb NOT NULL,
    cost_summary jsonb NOT NULL,
    status character varying(16) NOT NULL,
    error text,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_research_runs_status CHECK (((status)::text = ANY ((ARRAY['queued'::character varying, 'running'::character varying, 'completed'::character varying, 'failed'::character varying, 'cancelled'::character varying])::text[])))
);



CREATE TABLE public.research_sources (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    url text NOT NULL,
    url_hash character varying(64) NOT NULL,
    title text NOT NULL,
    raw_content text NOT NULL,
    source_type character varying(40) NOT NULL,
    metadata jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



ALTER TABLE ONLY public.document_chunks ALTER COLUMN id SET DEFAULT nextval('public.document_chunks_id_seq'::regclass);



ALTER TABLE ONLY public.research_events ALTER COLUMN id SET DEFAULT nextval('public.research_events_id_seq'::regclass);



ALTER TABLE ONLY public.research_evidence ALTER COLUMN id SET DEFAULT nextval('public.research_evidence_id_seq'::regclass);



ALTER TABLE ONLY public.alembic_version
    ADD CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num);



ALTER TABLE ONLY public.artifacts
    ADD CONSTRAINT artifacts_legacy_path_key UNIQUE (legacy_path);



ALTER TABLE ONLY public.artifacts
    ADD CONSTRAINT artifacts_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT document_chunks_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.embedding_profiles
    ADD CONSTRAINT embedding_profiles_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.migration_imports
    ADD CONSTRAINT migration_imports_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.reports
    ADD CONSTRAINT reports_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.reports
    ADD CONSTRAINT reports_public_id_key UNIQUE (public_id);



ALTER TABLE ONLY public.research_events
    ADD CONSTRAINT research_events_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.research_evidence
    ADD CONSTRAINT research_evidence_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.research_runs
    ADD CONSTRAINT research_runs_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.research_sources
    ADD CONSTRAINT research_sources_pkey PRIMARY KEY (id);



ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT uq_chat_messages_report_sequence UNIQUE (report_id, sequence);



ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT uq_document_chunks_index UNIQUE (document_id, chunk_index);



ALTER TABLE ONLY public.documents
    ADD CONSTRAINT uq_documents_filename_sha256 UNIQUE (filename, sha256);



ALTER TABLE ONLY public.embedding_profiles
    ADD CONSTRAINT uq_embedding_profile UNIQUE (provider, model, dimensions);



ALTER TABLE ONLY public.research_evidence
    ADD CONSTRAINT uq_evidence_run_number UNIQUE (run_id, number);



ALTER TABLE ONLY public.migration_imports
    ADD CONSTRAINT uq_migration_import_source_hash UNIQUE (source_path, sha256);



ALTER TABLE ONLY public.research_sources
    ADD CONSTRAINT uq_research_sources_run_url_hash UNIQUE (run_id, url_hash);



CREATE INDEX ix_artifacts_report_kind ON public.artifacts USING btree (report_id, kind);



CREATE INDEX ix_artifacts_run_kind ON public.artifacts USING btree (run_id, kind);



CREATE INDEX ix_document_chunks_document_id ON public.document_chunks USING btree (document_id);



CREATE INDEX ix_document_chunks_profile_id ON public.document_chunks USING btree (embedding_profile_id);



CREATE INDEX ix_documents_status ON public.documents USING btree (status);



CREATE INDEX ix_reports_updated_at ON public.reports USING btree (updated_at);



CREATE INDEX ix_research_events_run_id_id ON public.research_events USING btree (run_id, id);



CREATE INDEX ix_research_runs_report_id ON public.research_runs USING btree (report_id);



CREATE INDEX ix_research_runs_status ON public.research_runs USING btree (status);



CREATE INDEX ix_research_sources_run_id ON public.research_sources USING btree (run_id);



CREATE UNIQUE INDEX uq_embedding_profiles_one_active ON public.embedding_profiles USING btree (active) WHERE active;



ALTER TABLE ONLY public.artifacts
    ADD CONSTRAINT artifacts_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.reports(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.artifacts
    ADD CONSTRAINT artifacts_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.research_runs(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.reports(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT document_chunks_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT document_chunks_embedding_profile_id_fkey FOREIGN KEY (embedding_profile_id) REFERENCES public.embedding_profiles(id) ON DELETE RESTRICT;



ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_embedding_profile_id_fkey FOREIGN KEY (embedding_profile_id) REFERENCES public.embedding_profiles(id) ON DELETE SET NULL;



ALTER TABLE ONLY public.research_events
    ADD CONSTRAINT research_events_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.research_runs(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.research_evidence
    ADD CONSTRAINT research_evidence_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.research_runs(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.research_runs
    ADD CONSTRAINT research_runs_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.reports(id) ON DELETE CASCADE;



ALTER TABLE ONLY public.research_sources
    ADD CONSTRAINT research_sources_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.research_runs(id) ON DELETE CASCADE;




INSERT INTO public.alembic_version(version_num) VALUES ('0002_shared_library');
