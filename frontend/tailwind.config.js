/** @type {import('tailwindcss').Config} */
// Tailwind 3. Do NOT upgrade to 4 without rewriting this file and
// postcss.config.js -- v4 uses a completely different configuration model.
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      colors: {
        // ---------------------------------------------------------------
        // PawHome palette, taken from the Figma colour sheet.
        // The four named swatches there map exactly onto the first four
        // entries below -- the designer's own names are kept so a value can
        // be traced back to the source without guessing.
        // ---------------------------------------------------------------
        pw: {
          button: "#B20BF4", // "Button"  - primary action, the loudest colour
          hover: "#9908D2", //             darkened 12% for :hover
          sidebar: "#CE63F8", // "Sidebar" - role nav, filled headers
          stroke: "#DB8FFA", // "Stroke"  - card and input borders
          box: "#F4E3F8", // "Box"     - tinted panels, stat bands
          input: "#F7E7FE", //             input fill (lighter than Box)
          tint: "#FDF7FE", //             page background
          ink: "#1A1420", //             headings
          body: "#4B4453", //             body copy
          muted: "#8A7F93", //             captions, helper text
          danger: "#D42A4E", //             validation errors
          ok: "#15803D", //             success / password strength
        },
      },
      fontFamily: {
        // Outfit is the closest Google Font to the Figma type: geometric,
        // double-storey 'a', single-storey 'g'. If Figma names a different
        // face, change it HERE and in index.html -- nothing else references
        // a font name.
        sans: ["Outfit", "ui-sans-serif", "system-ui", "-apple-system", "Segoe UI", "sans-serif"],
      },
      borderRadius: {
        // The design uses fully-rounded inputs and buttons, and a soft 16-20px
        // on cards. "pill" reads better than "rounded-full" at call sites.
        pill: "9999px",
        card: "1.25rem",
      },
      boxShadow: {
        // "Drop Shad 30%" on the colour sheet.
        card: "0 1px 2px rgba(26,20,32,.04), 0 12px 28px -14px rgba(178,11,244,.30)",
        lift: "0 2px 4px rgba(26,20,32,.05), 0 18px 40px -18px rgba(178,11,244,.38)",
      },
    },
  },
  plugins: [],
};
