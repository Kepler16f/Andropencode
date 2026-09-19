export * as Watcher from "./watcher.android"

import { makeLocationNode } from "../effect/app-node"
import { Context, Effect, Layer } from "effect"
import { FileSystemWatcher } from "@opencode-ai/schema/filesystem-watcher"

export const Event = FileSystemWatcher.Event

export interface Interface {}

export class Service extends Context.Service<Service, Interface>()("@opencode/v2/FileWatcher") {}

// Android has no @parcel/watcher binding, so the watcher backend is an
// explicit no-op. Tool-written events still publish through EventV2 when the
// Location graph is replaced with a platform that does support watching.
const layer = Layer.effect(Service, Effect.sync(() => Service.of({})))

export const node = makeLocationNode({ service: Service, layer, deps: [] })