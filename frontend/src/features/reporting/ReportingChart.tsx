import { BarChart, PieChart } from 'echarts/charts';
import {
  AriaComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useRef } from 'react';

echarts.use([
  AriaComponent,
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

export interface ChartDatum {
  label: string;
  value: number;
}

interface ReportingChartProps {
  title: string;
  data: ChartDatum[];
  kind?: 'bar' | 'pie';
}

export function ReportingChart({ title, data, kind = 'bar' }: ReportingChartProps) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!container.current || data.length === 0) return undefined;
    const chart = echarts.init(container.current, undefined, { renderer: 'canvas' });
    chart.setOption(
      kind === 'pie'
        ? {
            aria: { enabled: true, decal: { show: true } },
            tooltip: { trigger: 'item', valueFormatter: (value: unknown) => String(value) },
            legend: { bottom: 0 },
            series: [
              {
                name: title,
                type: 'pie',
                radius: ['42%', '68%'],
                data: data.map((item) => ({ name: item.label, value: item.value })),
              },
            ],
          }
        : {
            aria: { enabled: true, decal: { show: true } },
            tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => String(value) },
            grid: { left: 32, right: 16, top: 16, bottom: 56, containLabel: true },
            xAxis: {
              type: 'category',
              data: data.map((item) => item.label),
              axisLabel: { interval: 0, rotate: data.length > 4 ? 24 : 0 },
            },
            yAxis: { type: 'value', minInterval: 1 },
            series: [
              {
                name: title,
                type: 'bar',
                data: data.map((item) => item.value),
                itemStyle: { color: '#315c45', borderRadius: [5, 5, 0, 0] },
              },
            ],
          },
    );
    const resize = () => chart.resize();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(resize);
    if (container.current) observer?.observe(container.current);
    window.addEventListener('resize', resize);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [data, kind, title]);

  if (data.length === 0) {
    return <p className="reporting-chart-empty">No data in the selected period.</p>;
  }

  return (
    <div className="reporting-chart-block">
      <div ref={container} className="reporting-chart" role="img" aria-label={`${title} chart`} />
      <details className="reporting-chart-fallback">
        <summary>{title} data table</summary>
        <table aria-label={`${title} data`}>
          <thead>
            <tr>
              <th scope="col">Category</th>
              <th scope="col">Count</th>
            </tr>
          </thead>
          <tbody>
            {data.map((item) => (
              <tr key={item.label}>
                <th scope="row">{item.label}</th>
                <td>{item.value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}
