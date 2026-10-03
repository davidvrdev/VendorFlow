import "server-only";
import { serverApiFetch } from "@/lib/api/server";
import type { NotificationView, Page } from "./types";

export const fetchNotifications = (page: number) =>
  serverApiFetch<Page<NotificationView>>(`/notifications?page=${Math.max(page - 1, 0)}&size=25`);
