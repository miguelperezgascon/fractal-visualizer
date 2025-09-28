# Fractal Visualizer

A **Mandelbrot & Julia** fractal visual explorer written in Java.

```
![](./assets/fm1.gif)
![](./assets/fj1.gif)
```

---

## Requirements

* Java JDK 8 or newer (Java 11+ recommended).
* No external libraries required.

---

## Build

From the project folder run:

```
javac FractalVisualizer.java
```

This produces `.class` files (including inner classes like `FractalVisualizer$1.class`, etc.).

---

## Run

```
java FractalVisualizer
```

These commands work on Linux, macOS and Windows as long as `javac`/`java` are on your PATH.

---

## Controls

* Mouse wheel — zoom centered on cursor
* Drag mouse — pan
* Arrow keys — pan
* `+` / `-` — increase / decrease max iterations
* `M` / `J` — switch Mandelbrot / Julia
* Left click (Julia mode) — set Julia parameter `c`
* `Space` — reset view

---

## Clean compiled files

Remove generated `.class` files (including inner classes) with:

Linux / macOS:

```
rm *.class
```

Windows (PowerShell):

```
Remove-Item *.class
```

Windows (CMD):

```
del *.class
```

---

## Notes

* The renderer is multithreaded and cancels previous renders for snappy interaction.
* Uses `double` precision: at extremely deep zooms floating-point precision limits may make details indistinguishable. Increasing iterations alone does not solve this; for arbitrary-precision zooms a different approach is required (out of scope for this minimal demo).

---
