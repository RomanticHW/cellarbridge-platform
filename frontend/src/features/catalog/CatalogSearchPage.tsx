import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  CatalogApiError,
  searchCatalog,
  type AvailabilityClass,
  type CatalogSearchQuery,
  type CatalogSku,
  type SupplySummary,
  type SupplyType,
} from '../../api/catalog';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const supplyTypes: SupplyType[] = [
  'DOMESTIC_ON_HAND',
  'BONDED_ON_HAND',
  'HONG_KONG_ON_HAND',
  'IN_TRANSIT_PRESALE',
  'OVERSEAS_SOURCING',
];
const availabilityClasses: AvailabilityClass[] = [
  'AVAILABLE',
  'LIMITED',
  'UNAVAILABLE',
  'REQUIRES_CONFIRMATION',
];
const sorts = ['relevance', 'name', '-updatedAt', 'vintage'] as const;

function displayEnum(value: string) {
  return value.replaceAll('_', ' ').toLowerCase();
}

function valuesFromUrl<T extends string>(
  searchParams: URLSearchParams,
  name: string,
  allowed: ReadonlyArray<T>,
): T[] {
  const values = searchParams.getAll(name).flatMap((value) => value.split(','));
  return values.filter((value): value is T => allowed.includes(value as T));
}

function queryFromUrl(searchParams: URLSearchParams): CatalogSearchQuery {
  const automatic = searchParams.get('automaticallyReservable');
  const sort = searchParams.get('sort');
  return {
    keyword: searchParams.get('keyword') || undefined,
    producer: searchParams.get('producer') || undefined,
    region: searchParams.get('region') || undefined,
    vintage: searchParams.get('vintage') || undefined,
    supplyType: valuesFromUrl(searchParams, 'supplyType', supplyTypes),
    availabilityClass: valuesFromUrl(searchParams, 'availabilityClass', availabilityClasses),
    automaticallyReservable:
      automatic === 'true' ? true : automatic === 'false' ? false : undefined,
    sort: sorts.includes(sort as (typeof sorts)[number])
      ? (sort as (typeof sorts)[number])
      : 'relevance',
    cursor: searchParams.get('cursor') || undefined,
    pageSize: 25,
  };
}

function timestamp(value?: string | null) {
  if (value === undefined || value === null) return 'Not provided';
  return new Date(value).toLocaleString();
}

function SupplyDetail({ supplies }: { supplies: SupplySummary[] }) {
  if (supplies.length === 0) {
    return (
      <Typography.Text type="secondary">No supply summary is currently visible.</Typography.Text>
    );
  }
  return (
    <Space orientation="vertical" size="middle" className="catalog-supply-list">
      {supplies.map((supply) => (
        <Card key={supply.supplyPoolId} size="small">
          <Space wrap>
            <Tag color={supply.automaticallyReservable ? 'green' : 'gold'}>
              {displayEnum(supply.supplyType)}
            </Tag>
            <Tag>{displayEnum(supply.availabilityLevel)}</Tag>
            <Typography.Text strong>{supply.locationLabel}</Typography.Text>
          </Space>
          <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }}>
            <Descriptions.Item label="Displayed quantity">
              {supply.displayedAvailableQuantity === undefined ||
              supply.displayedAvailableQuantity === null
                ? supply.displayQuantityBand
                : `${supply.displayedAvailableQuantity.value} ${supply.displayedAvailableQuantity.unit}`}
            </Descriptions.Item>
            <Descriptions.Item label="Reservation mode">
              {supply.automaticallyReservable
                ? 'Eligible for future automatic reservation'
                : 'Manual confirmation required'}
            </Descriptions.Item>
            <Descriptions.Item label="Estimated availability">
              {timestamp(supply.estimatedAvailableAt)}
            </Descriptions.Item>
            <Descriptions.Item label="Data as of">{timestamp(supply.updatedAt)}</Descriptions.Item>
          </Descriptions>
          {supply.exactLots.length > 0 && (
            <Collapse
              size="small"
              items={[
                {
                  key: 'lots',
                  label: `${supply.exactLots.length} authorized lot${supply.exactLots.length === 1 ? '' : 's'}`,
                  children: (
                    <Table
                      size="small"
                      pagination={false}
                      rowKey="lotId"
                      dataSource={supply.exactLots}
                      columns={[
                        { title: 'Lot', dataIndex: 'lotCode' },
                        { title: 'Warehouse', dataIndex: 'warehouseLabel' },
                        {
                          title: 'Available',
                          dataIndex: 'availableQuantity',
                          render: (quantity: { value: string; unit: string }) =>
                            `${quantity.value} ${quantity.unit}`,
                        },
                        {
                          title: 'Available from',
                          dataIndex: 'availableFrom',
                          render: (value: string | null | undefined) => timestamp(value),
                        },
                      ]}
                    />
                  ),
                },
              ]}
            />
          )}
        </Card>
      ))}
    </Space>
  );
}

export function CatalogSearchPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const [searchParams, setSearchParams] = useSearchParams();
  const keywordParam = searchParams.get('keyword') ?? '';
  const [keywordDraft, setKeywordDraft] = useState(keywordParam);
  const [cursorHistory, setCursorHistory] = useState<Array<string | undefined>>([]);
  const [selection, setSelection] = useState<CatalogSku[]>([]);
  const serializedQuery = searchParams.toString();
  const currentQuery = queryFromUrl(searchParams);

  useEffect(() => {
    const synchronizeKeyword = () => {
      setKeywordDraft(new URLSearchParams(window.location.search).get('keyword') ?? '');
    };
    window.addEventListener('popstate', synchronizeKeyword);
    return () => window.removeEventListener('popstate', synchronizeKeyword);
  }, []);

  useEffect(() => {
    const normalized = keywordDraft.trim();
    if (normalized === keywordParam) return;
    const timer = window.setTimeout(() => {
      const next = new URLSearchParams(searchParams);
      if (normalized === '') next.delete('keyword');
      else next.set('keyword', normalized);
      next.delete('cursor');
      setCursorHistory([]);
      setSearchParams(next, { replace: true });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [keywordDraft, keywordParam, searchParams, setSearchParams]);

  const catalog = useQuery({
    queryKey: ['catalog', 'search', serializedQuery],
    queryFn: ({ signal }) => searchCatalog(accessToken, currentQuery, signal),
    enabled: accessToken !== '',
  });

  const replaceFilter = (name: string, value?: string | ReadonlyArray<string>) => {
    const next = new URLSearchParams(searchParams);
    next.delete(name);
    if (Array.isArray(value)) value.forEach((item) => next.append(name, item));
    else if (typeof value === 'string' && value !== '') next.set(name, value);
    next.delete('cursor');
    setCursorHistory([]);
    setSearchParams(next);
  };
  const addSelection = (sku: CatalogSku) => {
    setSelection((current) =>
      current.some((item) => item.skuId === sku.skuId) ? current : [...current, sku],
    );
  };
  const nextPage = () => {
    const nextCursor = catalog.data?.pageInfo.nextCursor;
    if (nextCursor === null || nextCursor === undefined) return;
    setCursorHistory((history) => [...history, currentQuery.cursor]);
    const next = new URLSearchParams(searchParams);
    next.set('cursor', nextCursor);
    setSearchParams(next);
  };
  const previousPage = () => {
    const previous = cursorHistory.at(-1);
    const next = new URLSearchParams(searchParams);
    if (previous === undefined) next.delete('cursor');
    else next.set('cursor', previous);
    setCursorHistory((history) => history.slice(0, -1));
    setSearchParams(next);
  };

  if (catalog.isPending) return <LoadingState message="Searching catalog and supply…" />;
  if (catalog.isError) {
    const problem = catalog.error instanceof CatalogApiError ? catalog.error : undefined;
    return (
      <ErrorState
        title={
          problem?.status === 403 ? 'Catalog supply access denied' : 'Catalog search is unavailable'
        }
        description={
          problem === undefined
            ? 'The catalog could not be searched for the current tenant.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void catalog.refetch()}
      />
    );
  }

  return (
    <section className="catalog-page" aria-labelledby="catalog-search-title">
      <Space orientation="vertical" size="large" className="catalog-page-stack">
        <div>
          <Typography.Title id="catalog-search-title" level={2}>
            Catalog & supply search
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            Find active SKUs and inspect tenant-scoped, non-committing supply visibility.
          </Typography.Paragraph>
        </div>

        <div className="catalog-filters" role="search" aria-label="Catalog filters">
          <Input
            aria-label="Search catalog"
            placeholder="Wine, SKU, producer, region or tag"
            value={keywordDraft}
            allowClear
            onChange={(event) => setKeywordDraft(event.target.value)}
          />
          <Input
            aria-label="Filter by producer"
            placeholder="Producer"
            value={currentQuery.producer ?? ''}
            allowClear
            onChange={(event) => replaceFilter('producer', event.target.value)}
          />
          <Input
            aria-label="Filter by region"
            placeholder="Region"
            value={currentQuery.region ?? ''}
            allowClear
            onChange={(event) => replaceFilter('region', event.target.value)}
          />
          <Input
            aria-label="Filter by vintage"
            placeholder="Vintage or NV"
            value={currentQuery.vintage ?? ''}
            allowClear
            onChange={(event) => replaceFilter('vintage', event.target.value.toUpperCase())}
          />
          <Select
            aria-label="Filter by supply type"
            mode="multiple"
            placeholder="All supply types"
            value={currentQuery.supplyType ?? []}
            onChange={(value) => replaceFilter('supplyType', value)}
            options={supplyTypes.map((value) => ({ value, label: displayEnum(value) }))}
          />
          <Select
            aria-label="Filter by availability"
            mode="multiple"
            placeholder="All availability classes"
            value={currentQuery.availabilityClass ?? []}
            onChange={(value) => replaceFilter('availabilityClass', value)}
            options={availabilityClasses.map((value) => ({
              value,
              label: displayEnum(value),
            }))}
          />
          <Select
            aria-label="Sort catalog"
            value={currentQuery.sort ?? 'relevance'}
            onChange={(value) => replaceFilter('sort', value)}
            options={sorts.map((value) => ({ value, label: displayEnum(value) }))}
          />
          <Checkbox
            checked={currentQuery.automaticallyReservable === true}
            onChange={(event) =>
              replaceFilter('automaticallyReservable', event.target.checked ? 'true' : undefined)
            }
          >
            Future automatic-reservation routes only
          </Checkbox>
        </div>

        <Alert
          type="warning"
          showIcon
          title={catalog.data.availabilityDisclaimer}
          description={`Latest visible supply data: ${timestamp(catalog.data.dataAsOf)}`}
        />

        <div className="catalog-workspace">
          <div>
            {catalog.data.items.length === 0 ? (
              <Card>
                <Empty description="No active SKUs match the current filters." />
              </Card>
            ) : (
              <div className="catalog-results" aria-label="Catalog search results">
                {catalog.data.items.map((item) => (
                  <Card
                    key={item.sku.skuId}
                    className="catalog-result-card"
                    title={
                      <span>
                        {item.sku.displayName}
                        <Typography.Text type="secondary" className="catalog-sku-code">
                          {item.sku.skuCode}
                        </Typography.Text>
                      </span>
                    }
                    extra={
                      <Button onClick={() => addSelection(item.sku)}>Add to quote selection</Button>
                    }
                  >
                    <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 4 }}>
                      <Descriptions.Item label="Producer">
                        {item.sku.producerName ?? '—'}
                      </Descriptions.Item>
                      <Descriptions.Item label="Region">
                        {item.sku.regionName ?? '—'}
                      </Descriptions.Item>
                      <Descriptions.Item label="Vintage">{item.sku.vintage}</Descriptions.Item>
                      <Descriptions.Item label="Format">
                        {item.sku.volumeMl} ml × {item.sku.unitsPerCase}
                      </Descriptions.Item>
                    </Descriptions>
                    <SupplyDetail supplies={item.supplies} />
                  </Card>
                ))}
              </div>
            )}
            <Space className="catalog-pagination">
              <Button disabled={cursorHistory.length === 0} onClick={previousPage}>
                Previous
              </Button>
              <Button disabled={!catalog.data.pageInfo.hasNext} onClick={nextPage}>
                Next
              </Button>
            </Space>
          </div>

          <Card title={`Pending quote selection (${selection.length})`} className="quote-selection">
            <Typography.Paragraph type="secondary">
              Local working state only. No quotation, reservation, or order has been created.
            </Typography.Paragraph>
            {selection.length === 0 ? (
              <Typography.Text type="secondary">
                Add an SKU from the search results to prepare the next workflow.
              </Typography.Text>
            ) : (
              <Space orientation="vertical" className="quote-selection-list">
                {selection.map((sku) => (
                  <div key={sku.skuId} className="quote-selection-item">
                    <span>
                      <Typography.Text strong>{sku.displayName}</Typography.Text>
                      <Typography.Text type="secondary">{sku.skuCode}</Typography.Text>
                    </span>
                    <Button
                      type="text"
                      danger
                      aria-label={`Remove ${sku.skuCode} from quote selection`}
                      onClick={() =>
                        setSelection((current) =>
                          current.filter((item) => item.skuId !== sku.skuId),
                        )
                      }
                    >
                      Remove
                    </Button>
                  </div>
                ))}
                <Link
                  to={`/app/quotations/new?skuIds=${selection.map((sku) => sku.skuId).join(',')}`}
                >
                  <Button type="primary" block>
                    Create quotation with selection
                  </Button>
                </Link>
              </Space>
            )}
          </Card>
        </div>
      </Space>
    </section>
  );
}
