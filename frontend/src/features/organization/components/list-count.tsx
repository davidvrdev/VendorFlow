/** "Showing N of M" when the server has more rows than the first page we render. */
export function ListCount({ shown, total }: { shown: number; total: number }) {
  if (total <= shown) return null;
  return (
    <p className="mt-2 text-sm text-muted-foreground" role="status">
      Showing {shown} of {total}
    </p>
  );
}
