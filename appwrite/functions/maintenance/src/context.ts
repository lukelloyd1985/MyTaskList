export interface RequestContext {
  bodyJson?: unknown;
  headers: Record<string, string>;
  path: string;
}

export interface ResponseContext {
  json: (data: unknown, statusCode?: number) => unknown;
}

export interface FunctionContext {
  req: RequestContext;
  res: ResponseContext;
  log: (message: unknown) => void;
  error: (message: unknown) => void;
}
