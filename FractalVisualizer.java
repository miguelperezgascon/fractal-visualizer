/*
 FractalVisualizer.java — minimal, fast Mandelbrot/Julia explorer

 Compile:
   javac FractalVisualizer.java
 Run:
   java FractalVisualizer

 Guide:
 - Mandelbrot and Julia sets (toggle with M/J). For Julia, click to set 'c'.
 - Mouse-wheel zoom centered on cursor, arrow keys to pan, +/- to change max iterations
 - Responsive: multithreaded renderer that cancels previous renders when you interact
*/

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/*
* Main class: JPanel with rendering and interaction
*/
public class FractalVisualizer extends JPanel {
    // Canvas size
    static final int W = 900;
    static final int H = 600;

    // Render parameters (volatile for UI thread safety)
    volatile double centerX = -0.5;
    volatile double centerY = 0.0;
    volatile double scale = 3.0; // width of view in complex plane
    volatile int maxIter = 400;
    volatile boolean useJulia = false;
    volatile double juliaCr = -0.8, juliaCi = 0.156;

    // Rendering
    volatile BufferedImage image;
    final ExecutorService pool;
    final int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
    final AtomicInteger renderId = new AtomicInteger(0);

    // Interaction
    Point lastMouse;

    // UI tuning
    final double zoomFactorPerNotch = 1.2;
    final double panFraction = 0.15; // fraction of view to pan with arrows

    public FractalVisualizer(){
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        requestFocusInWindow();

        pool = Executors.newFixedThreadPool(threads);
        image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);

        // Mouse wheel zoom centered at mouse
        addMouseWheelListener(e -> {
            int notches = e.getWheelRotation();
            double mouseRe = screenToRe(e.getX());
            double mouseIm = screenToIm(e.getY());
            double oldScale = scale;
            if(notches < 0) scale /= Math.pow(zoomFactorPerNotch, -notches);
            else scale *= Math.pow(zoomFactorPerNotch, notches);
            // adjust center so mouse point stays fixed (mathematically exact)
            centerX = mouseRe + (centerX - mouseRe) * (scale / oldScale);
            centerY = mouseIm + (centerY - mouseIm) * (scale / oldScale);
            triggerRender();
        });

        // Click to set Julia parameter (when in Julia mode)
        addMouseListener(new MouseAdapter(){
            @Override
            public void mousePressed(MouseEvent e){
                if(useJulia && SwingUtilities.isLeftMouseButton(e)){
                    juliaCr = screenToRe(e.getX());
                    juliaCi = screenToIm(e.getY());
                    triggerRender();
                }
                lastMouse = e.getPoint();
            }
        });

        // Drag for pan
        addMouseMotionListener(new MouseMotionAdapter(){
            @Override
            public void mouseDragged(MouseEvent e){
                if(lastMouse != null){
                    int dx = e.getX() - lastMouse.x;
                    int dy = e.getY() - lastMouse.y;
                    // translate pixels to complex plane units
                    centerX -= dx * (scale / W);
                    centerY -= dy * (scale / W);
                    lastMouse = e.getPoint();
                    triggerRender();
                }
            }
        });

        // Keyboard controls
        addKeyListener(new KeyAdapter(){
            @Override
            public void keyPressed(KeyEvent e){
                switch(e.getKeyCode()){
                    case KeyEvent.VK_LEFT:
                        centerX -= panFraction * scale; triggerRender(); break;
                    case KeyEvent.VK_RIGHT:
                        centerX += panFraction * scale; triggerRender(); break;
                    case KeyEvent.VK_UP:
                        centerY -= panFraction * scale; triggerRender(); break;
                    case KeyEvent.VK_DOWN:
                        centerY += panFraction * scale; triggerRender(); break;
                    case KeyEvent.VK_PLUS: case KeyEvent.VK_EQUALS: // +
                        maxIter = Math.min(2000, (int)(maxIter * 1.25)); triggerRender(); break;
                    case KeyEvent.VK_MINUS:
                        maxIter = Math.max(50, (int)(maxIter / 1.25)); triggerRender(); break;
                    case KeyEvent.VK_M:
                        useJulia = false; triggerRender(); break;
                    case KeyEvent.VK_J:
                        useJulia = true; triggerRender(); break;
                    case KeyEvent.VK_SPACE:
                        // reset
                        centerX = -0.5; centerY = 0.0; scale = 3.0; maxIter = 400; useJulia = false; triggerRender(); break;
                }
            }
        });

        // initial render
        triggerRender();
    }

    // map pixel x to real
    double screenToRe(int sx){
        return centerX + ( (sx - W/2.0) * (scale / W) );
    }
    // map pixel y to imag (note y increases downward)
    double screenToIm(int sy){
        double aspect = (double)H / W;
        return centerY + ( (sy - H/2.0) * (scale * aspect / H) );
    }

    // Trigger a new render: cancel previous by incrementing renderId
    void triggerRender(){
        final int id = renderId.incrementAndGet();
        final double cX = centerX, cY = centerY, sc = scale;
        final int iters = maxIter;
        final boolean julia = useJulia;
        final double jc = juliaCr, ji = juliaCi;

        // Render preview (subsample 4) y full (subsample 1), cada uno en su BufferedImage
        submitRender(id, cX, cY, sc, iters, julia, jc, ji, 4);
        submitRender(id, cX, cY, sc, iters, julia, jc, ji, 1);
    }

    void submitRender(int id, double cX, double cY, double sc, int iters, boolean julia, double jc, double ji, int subsample){
        final int tileHeight = Math.max(8, H / (threads * 4));
        final int tileCount = (H + tileHeight - 1) / tileHeight;

        // temporary image exclusive to this render
        final BufferedImage renderImage = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        final AtomicInteger remaining = new AtomicInteger(tileCount);

        for(int ty = 0; ty < H; ty += tileHeight){
            final int y0 = ty, y1 = Math.min(H, ty + tileHeight);
            pool.submit(() -> {
                // If this render was cancelled, exit early
                if(renderId.get() != id) return;
                renderTile(renderImage, y0, y1, cX, cY, sc, iters, julia, jc, ji, subsample);

                // decrement y if all tiles are done, publish the full image
                if(remaining.decrementAndGet() == 0){
                    // Only replace the visible image if the id is still the same (no other interaction has occurred)
                    if(renderId.get() == id){
                        image = renderImage; // volatile write
                        SwingUtilities.invokeLater(this::repaint);
                    }
                }
            });
        }
    }

    // Core renderer for rows [y0, y1)
    void renderTile(BufferedImage img, int y0, int y1, double cX, double cY, double sc, int maxIter,
                    boolean julia, double jc, double ji, int subsample){
        final double aspect = (double)H / W;
        final double reFactor = sc / W;
        final double imFactor = sc * aspect / H;

        for(int py = y0; py < y1; py += subsample){
            int yy = py;
            for(int px = 0; px < W; px += subsample){
                // compute for upper-left pixel per block
                double x0 = cX + ((px - W/2.0) * reFactor);
                double y0c = cY + ((py - H/2.0) * imFactor);

                double zx = x0, zy = y0c;
                double cx = julia ? jc : x0;
                double cy = julia ? ji : y0c;

                int iter = 0;
                double zx2 = zx*zx, zy2 = zy*zy;
                // iterate
                while(iter < maxIter && zx2 + zy2 <= 4.0){
                    zy = 2*zx*zy + cy;
                    zx = zx2 - zy2 + cx;
                    zx2 = zx*zx; zy2 = zy*zy;
                    iter++;
                }

                int rgb;
                if(iter >= maxIter){
                    rgb = 0x000000; // inside -> black
                } else {
                    // smooth iteration count for continuous coloring
                    double log_zn = Math.log(zx2 + zy2) / 2.0;
                    double nu = iter + 1 - Math.log(log_zn) / Math.log(2);
                    // color mapping: convert continuous index to HSB
                    float hue = (float)(0.95f + 10 * nu / maxIter) % 1f;
                    float sat = 0.8f;
                    float bright = 0.7f;
                    rgb = Color.HSBtoRGB(hue, sat, bright);
                }

                // write block of size subsample x subsample
                for(int dy = 0; dy < subsample; dy++){
                    int sy = yy + dy;
                    if(sy >= H) break;
                    for(int dx = 0; dx < subsample; dx++){
                        int sx = px + dx;
                        if(sx >= W) break;
                        img.setRGB(sx, sy, rgb);
                    }
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g){
        super.paintComponent(g);
        g.drawImage(image, 0, 0, null);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(255,255,255,200));
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

    String mode = useJulia ? "Julia" : "Mandelbrot";
    String coords = useJulia
        ? String.format("c=%.6f%+.6fi", juliaCr, juliaCi)
        : String.format("cx=%.6f cy=%.6f", centerX, centerY);
    String info = String.format("%s  %s  scale=%.6g  iter=%d", mode, coords, scale, maxIter);
        g2.drawString(info, 8, 18);
        g2.drawString("Mouse-wheel: zoom  |  Arrows: pan  |  +/- iter  |  M/J: mode  |  Click to set Julia c  |  Space: reset", 8, H-8);
        g2.dispose();
    }

    // Entry point
    public static void main(String[] args){
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Fractal Visualizer — Mandelbrot & Julia");
            FractalVisualizer panel = new FractalVisualizer();
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(panel);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            panel.requestFocusInWindow();
        });
    }
}