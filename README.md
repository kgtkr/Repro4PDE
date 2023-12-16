# Repro4PDE
日本語README: [README_ja.md](README_ja.md)

![demo](./demo.png)
[demo video](./demo.mp4)

## Usage
1. Open sketch
2. Select Repro4PDE from the tools menu (Untitled sketches are not supported)
3. Press the play button on the opened window(Not a play button in Processing Editor)
4. Edit and save your sketch and the program will automatically restart and hot reload.

The random number seed value is fixed for program reproducibility. You can change to another seed value by pressing the reset button. We plan to implement similar functionality in the time API, etc.

## Comparison with current code
By pressing `Enable comparison with current code`, you can check the sketch, code, and behavior at the same time when you press the button. Events etc. are synchronized, making it easier to check operation.

## Limitation
* Does not support frame rate changes (only 60fps)
* It is not supported to specify the renderer with the third argument of `size`
* Programs with high CPU load may not work
* Operation when combined with libraries has not been confirmed
* `draw` is required (`noLoop` can't be used either)
* Must call `background` every frame (please reset the screen)

We plan to respond gradually.
