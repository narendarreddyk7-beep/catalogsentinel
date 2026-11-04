import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Route, Routes } from "react-router";
import { ApiError } from "./api/client";
import { Shell } from "./components/Shell";
import { Empty } from "./components/States";
import { BatchJobsPage } from "./pages/BatchJobs";
import { CatalogExplorerPage } from "./pages/CatalogExplorer";
import { OverviewPage } from "./pages/Overview";
import { ProductMatchingPage } from "./pages/ProductMatching";
import { SelectionGapsPage } from "./pages/SelectionGaps";
import { SystemHealthPage } from "./pages/SystemHealth";

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        // Retry transient failures only; a 4xx will not fix itself.
        retry: (failures, error) => failures < 2 && (!(error instanceof ApiError) || error.status === 0 || error.status >= 500),
        refetchOnWindowFocus: false,
      },
    },
  });
}

export function AppRoutes() {
  return (
    <Shell>
      <Routes>
        <Route path="/" element={<OverviewPage />} />
        <Route path="/catalog" element={<CatalogExplorerPage />} />
        <Route path="/catalog/listings/:listingId" element={<CatalogExplorerPage />} />
        <Route path="/gaps" element={<SelectionGapsPage />} />
        <Route path="/gaps/:gapId" element={<SelectionGapsPage />} />
        <Route path="/matching" element={<ProductMatchingPage view="queue" />} />
        <Route path="/matching/products" element={<ProductMatchingPage view="products" />} />
        <Route path="/matching/products/:productId" element={<ProductMatchingPage view="products" />} />
        <Route path="/matching/:candidateId" element={<ProductMatchingPage view="queue" />} />
        <Route path="/jobs" element={<BatchJobsPage />} />
        <Route path="/jobs/ingestion/:jobId" element={<BatchJobsPage />} />
        <Route path="/health" element={<SystemHealthPage />} />
        <Route path="*" element={<Empty title="Page not found">Use the navigation to pick a view.</Empty>} />
      </Routes>
    </Shell>
  );
}

export function App({ client = createQueryClient() }: { client?: QueryClient }) {
  return (
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  );
}
