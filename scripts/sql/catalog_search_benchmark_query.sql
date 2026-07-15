EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT s.id, s.code, wp.name,
       round((ts_rank_cd(s.search_document, websearch_to_tsquery('simple', 'starling'))
              + word_similarity('starling', s.search_text))::numeric, 6) AS relevance_score
  FROM catalog.sku s
  JOIN catalog.wine_product wp
    ON wp.tenant_id = s.tenant_id
   AND wp.id = s.product_id
 WHERE s.tenant_id = '90000000-0000-4000-8000-000000000001'
   AND s.status = 'ACTIVE'
   AND s.id IN (
       SELECT keyword_match.id
         FROM (
           SELECT ks.id
             FROM catalog.sku ks
            WHERE ks.tenant_id = '90000000-0000-4000-8000-000000000001'
              AND ks.status = 'ACTIVE'
              AND ks.search_document @@ websearch_to_tsquery('simple', 'starling')
           UNION
           SELECT ks.id
             FROM catalog.sku ks
            WHERE ks.tenant_id = '90000000-0000-4000-8000-000000000001'
              AND ks.status = 'ACTIVE'
              AND ks.search_text % 'starling'
           UNION
           SELECT ks.id
             FROM catalog.sku ks
            WHERE ks.tenant_id = '90000000-0000-4000-8000-000000000001'
              AND ks.status = 'ACTIVE'
              AND ks.search_text %> 'starling'
         ) AS keyword_match)
   AND EXISTS (
       SELECT 1
         FROM catalog.sku_supply_projection sp
        WHERE sp.tenant_id = s.tenant_id
          AND sp.sku_id = s.id
          AND sp.quantity_unit = 'BOTTLE'
          AND sp.supply_type = 'DOMESTIC_ON_HAND'
          AND sp.availability_class = 'AVAILABLE'
          AND sp.automatically_reservable = true)
 ORDER BY relevance_score DESC, s.code, s.id
 LIMIT 26;
