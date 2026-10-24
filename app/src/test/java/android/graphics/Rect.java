package android.graphics;

/** Functional geometry for local JVM tests; Android's mockable jar leaves constructors empty. */
public class Rect {
    public int left, top, right, bottom;
    public Rect() {}
    public Rect(int left, int top, int right, int bottom) { set(left, top, right, bottom); }
    public Rect(Rect other) { this(other.left, other.top, other.right, other.bottom); }
    public void set(int left, int top, int right, int bottom) {
        this.left=left; this.top=top; this.right=right; this.bottom=bottom;
    }
    public void set(Rect other) { set(other.left,other.top,other.right,other.bottom); }
    public void setEmpty() { set(0,0,0,0); }
    public int width() { return right-left; }
    public int height() { return bottom-top; }
    public int centerX() { return (left+right)>>1; }
    public int centerY() { return (top+bottom)>>1; }
    public boolean isEmpty() { return left>=right || top>=bottom; }
    public boolean contains(int x,int y) { return !isEmpty() && left<=x && x<right && top<=y && y<bottom; }
    public boolean contains(Rect other) { return !isEmpty() && left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom; }
    public boolean contains(int l,int t,int r,int b) { return contains(new Rect(l,t,r,b)); }
    public static boolean intersects(Rect a,Rect b) { return a.left<b.right && b.left<a.right && a.top<b.bottom && b.top<a.bottom; }
    public boolean intersect(Rect other) { return intersect(other.left,other.top,other.right,other.bottom); }
    public boolean intersect(int l,int t,int r,int b) {
        if(left<r && l<right && top<b && t<bottom) { set(Math.max(left,l),Math.max(top,t),Math.min(right,r),Math.min(bottom,b));return true; }
        return false;
    }
    public void union(Rect other) {
        if(other.isEmpty())return;
        if(isEmpty())set(other);else set(Math.min(left,other.left),Math.min(top,other.top),Math.max(right,other.right),Math.max(bottom,other.bottom));
    }
    public void offset(int dx,int dy) { left+=dx;right+=dx;top+=dy;bottom+=dy; }
    public void offsetTo(int x,int y) { offset(x-left,y-top); }
    public void inset(int dx,int dy) { left+=dx;right-=dx;top+=dy;bottom-=dy; }
    @Override public boolean equals(Object other) { if(!(other instanceof Rect))return false;Rect r=(Rect)other;return left==r.left && top==r.top && right==r.right && bottom==r.bottom; }
    @Override public int hashCode() { return (((left*31)+top)*31+right)*31+bottom; }
    @Override public String toString() { return "Rect("+left+", "+top+" - "+right+", "+bottom+")"; }
    public String toShortString() { return "["+left+","+top+"]["+right+","+bottom+"]"; }
}
