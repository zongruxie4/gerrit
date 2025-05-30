/**
 * @license
 * Copyright 2020 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {
  AccountDetailInfo,
  HttpMethod,
  ProjectInfoWithName,
  ServerInfo,
} from './rest-api';

export {HttpMethod};

export type RequestPayload = string | object;

export type ErrorCallback = (
  response?: Response | null,
  err?: Error
) => Promise<void> | void;

export declare interface RestPluginApi {
  getLoggedIn(): Promise<boolean>;

  getVersion(): Promise<string | undefined>;

  getConfig(): Promise<ServerInfo | undefined>;

  invalidateReposCache(): void;

  getAccount(): Promise<AccountDetailInfo | undefined>;

  getRepos(
    filter: string,
    reposPerPage: number,
    offset?: number
  ): Promise<ProjectInfoWithName[] | undefined>;

  fetch(
    method: HttpMethod,
    url: string,
    payload?: RequestPayload,
    errFn?: undefined,
    contentType?: string
  ): Promise<Response>;

  fetch(
    method: HttpMethod,
    url: string,
    payload: RequestPayload | undefined,
    errFn: ErrorCallback,
    contentType?: string
  ): Promise<Response | void>;

  fetch(
    method: HttpMethod,
    url: string,
    payload: RequestPayload | undefined,
    errFn?: ErrorCallback,
    contentType?: string
  ): Promise<Response | void>;

  /**
   * Fetch and return native browser REST API Response.
   */
  fetch(
    method: HttpMethod,
    url: string,
    payload?: RequestPayload,
    errFn?: ErrorCallback,
    contentType?: string
  ): Promise<Response | void>;

  /**
   * Fetch and parse REST API response, if request succeeds.
   */
  send<T>(
    method: HttpMethod,
    url: string,
    payload?: RequestPayload,
    errFn?: ErrorCallback,
    contentType?: string
  ): Promise<T>;

  get<T>(url: string): Promise<T>;

  post<T>(
    url: string,
    payload?: RequestPayload,
    errFn?: ErrorCallback,
    contentType?: string
  ): Promise<T>;

  put<T>(
    url: string,
    payload?: RequestPayload,
    errFn?: ErrorCallback,
    contentType?: string
  ): Promise<T>;

  delete(url: string): Promise<Response>;
}
