import type { ReactNode } from "react";
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from "@/components/ui/card";

interface AuthCardProps {
  title: string;
  description?: ReactNode;
  footer?: ReactNode;
  children?: ReactNode;
}

export function AuthCard({ title, description, footer, children }: AuthCardProps) {
  return (
    <Card>
      <CardHeader>
        {/* The page's only h1 (CardTitle renders a div, so style an h1 the same way). */}
        <h1 data-slot="card-title" className="font-heading text-xl leading-snug font-semibold tracking-tight">
          {title}
        </h1>
        {description ? <CardDescription>{description}</CardDescription> : null}
      </CardHeader>
      {children ? <CardContent className="grid gap-4">{children}</CardContent> : null}
      {footer ? <CardFooter className="flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground">{footer}</CardFooter> : null}
    </Card>
  );
}
