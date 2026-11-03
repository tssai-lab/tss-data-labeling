export function isPublicAuthenticationRequest(error: unknown): boolean;
export function isUnauthorizedResponse(error: unknown): boolean;
export function createUnauthorizedOnceGate(): {
  run(currentToken: string | null, effect: () => void): boolean;
  isHandled(): boolean;
};

export function shouldNotifySessionExpired(token: string | null, silent?: boolean): boolean;
