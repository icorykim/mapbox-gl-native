#include <mbgl/platform/default/headless_display.hpp>

#if MBGL_USE_EGL
#include <mbgl/util/string.hpp>
#include <EGL/egl.h>
#include <fcntl.h>
#include <unistd.h>
#endif

#include <cstring>
#include <stdexcept>
#include <string>

namespace mbgl {

HeadlessDisplay::HeadlessDisplay() {
#if MBGL_USE_CGL
    // TODO: test if OpenGL 4.1 with GL_ARB_ES2_compatibility is supported
    // If it is, use kCGLOGLPVersion_3_2_Core and enable that extension.
    CGLPixelFormatAttribute attributes[] = {
        kCGLPFAOpenGLProfile,
        static_cast<CGLPixelFormatAttribute>(kCGLOGLPVersion_Legacy),
        static_cast<CGLPixelFormatAttribute>(0)
    };

    GLint num;
    CGLError error = CGLChoosePixelFormat(attributes, &pixelFormat, &num);
    if (error != kCGLNoError) {
        throw std::runtime_error(std::string("Error choosing pixel format:") + CGLErrorString(error) + "\n");
    }
    if (num <= 0) {
        throw std::runtime_error("No pixel formats found.");
    }
#endif
#if MBGL_USE_EGL
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) {
        throw std::runtime_error("eglGetDisplay() failed.");
    }

    EGLint major, minor, n;
    if (!eglInitialize(dpy, &major, &minor)) {
        throw std::runtime_error("eglInitialize() failed.");
    }

    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_DONT_CARE,
        EGL_RED_SIZE, 1,
        EGL_GREEN_SIZE, 1,
        EGL_BLUE_SIZE, 1,
        EGL_ALPHA_SIZE, 0,
        EGL_DEPTH_SIZE, 1,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_NONE
    };
    if (!eglChooseConfig(dpy, attribs, &config, 1, &n) || n != 1) {
        throw std::runtime_error("Failed to choose argb config.");
    }
#endif
}

HeadlessDisplay::~HeadlessDisplay() {
#if MBGL_USE_CGL
    CGLDestroyPixelFormat(pixelFormat);
#endif
#if MBGL_USE_EGL
    eglTerminate(dpy);
#endif
}

} // namespace mbgl
