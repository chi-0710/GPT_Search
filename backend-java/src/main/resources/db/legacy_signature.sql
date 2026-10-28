SELECT jsonb_build_object(
  'columns', (SELECT jsonb_agg(jsonb_build_array(c.relname,a.attname,
      pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,
      coalesce(pg_get_expr(d.adbin,d.adrelid),'')) ORDER BY c.relname,a.attnum)
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
    WHERE n.nspname='public' AND c.relkind='r' AND c.relname<>'flyway_schema_history'),
  'constraints', (SELECT jsonb_agg(jsonb_build_array(c.relname,k.conname,pg_get_constraintdef(k.oid))
      ORDER BY c.relname,k.conname)
    FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relname<>'flyway_schema_history'),
  'indexes', (SELECT jsonb_agg(jsonb_build_array(tablename,indexname,indexdef) ORDER BY tablename,indexname)
    FROM pg_indexes WHERE schemaname='public' AND tablename<>'flyway_schema_history')
)::text
