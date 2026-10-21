import { createTheme, type ThemeOptions } from '@mui/material/styles'

/** Brand: primary Blue / Black / White; secondary Orange / Tangerine. Fonts: Candara, Georgia, Tempus Sans ITC. */
const ORANGE = '#FF7A00'
const TANGERINE = '#FF5C00'
const BLACK = '#0A0A0A'
const WHITE = '#FFFFFF'
/** Sampled from the OuWealth “Wealth” wordmark. */
const BLUE = '#1B4D8C'
const BLUE_DARK = '#143A6B'
const BLUE_LIGHT = '#4A7AB8'

const fontBody = "Candara, Calibri, 'Segoe UI', sans-serif"
const fontHeading = "Georgia, 'Times New Roman', serif"
const fontBrand = "'Tempus Sans ITC', Georgia, 'Times New Roman', serif"

const base: ThemeOptions = {
  breakpoints: {
    values: {
      xs: 0,
      sm: 600,
      md: 900,
      lg: 1200,
      xl: 1536,
    },
  },
  typography: {
    fontFamily: fontBody,
    h1: { fontFamily: fontHeading, fontWeight: 700 },
    h2: { fontFamily: fontHeading, fontWeight: 700 },
    h3: { fontFamily: fontHeading, fontWeight: 650 },
    h4: { fontFamily: fontHeading, fontWeight: 650 },
    h5: { fontFamily: fontHeading, fontWeight: 650 },
    h6: { fontFamily: fontBrand, fontWeight: 600 },
    button: { textTransform: 'none', fontWeight: 600, fontFamily: fontBody },
  },
  shape: { borderRadius: 10 },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          fontFamily: fontBody,
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          backgroundColor: BLACK,
          color: WHITE,
          backgroundImage: 'none',
        },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          borderRadius: 8,
          paddingInline: 18,
          minHeight: 40,
        },
        sizeSmall: { minHeight: 36 },
        sizeLarge: { minHeight: 48 },
        contained: {
          backgroundColor: BLUE,
          color: WHITE,
          '&:hover': { backgroundColor: BLUE_DARK },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: { minWidth: 40, minHeight: 40 },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: { minHeight: 44 },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: 'none' },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: { borderRight: '1px solid var(--color-border)' },
      },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: '1px solid',
          borderColor: 'divider',
        },
      },
    },
  },
}

export const lightTheme = createTheme({
  ...base,
  palette: {
    mode: 'light',
    primary: {
      main: BLUE,
      dark: BLUE_DARK,
      light: BLUE_LIGHT,
      contrastText: WHITE,
    },
    secondary: {
      main: ORANGE,
      dark: '#CC5200',
      light: '#FF9A3D',
      contrastText: WHITE,
    },
    background: {
      default: WHITE,
      paper: WHITE,
    },
    text: {
      primary: BLACK,
      secondary: '#4A4A4A',
    },
    divider: '#D7E3F4',
    success: { main: '#2E7D32' },
    warning: { main: TANGERINE },
    error: { main: '#C62828' },
    info: { main: BLUE },
  },
})

export const darkTheme = createTheme({
  ...base,
  palette: {
    mode: 'dark',
    primary: {
      main: BLUE_LIGHT,
      dark: BLUE,
      light: '#8FB3DC',
      contrastText: WHITE,
    },
    secondary: {
      main: '#FF9A3D',
      dark: ORANGE,
      contrastText: BLACK,
    },
    background: {
      default: BLACK,
      paper: '#161616',
    },
    text: {
      primary: WHITE,
      secondary: '#C4C4C4',
    },
    divider: '#2E2E2E',
    success: { main: '#66BB6A' },
    warning: { main: TANGERINE },
    error: { main: '#EF5350' },
    info: { main: BLUE_LIGHT },
  },
})
