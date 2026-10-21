import { Box, Typography } from '@mui/material'
import { useAuthSurface } from '@/layouts/authSurface'
import { BrandHomeLink } from '@/shared/components/BrandHomeLink'
import { BrandLogo } from '@/shared/components/BrandLogo'

interface AuthBrandProps {
  title: string
  tagline?: string
  subtitle?: string
}

export function AuthBrand({ title, tagline, subtitle }: AuthBrandProps) {
  const { onDark } = useAuthSurface()

  return (
    <Box sx={{ textAlign: 'center' }}>
      <Box
        component="h1"
        sx={{
          m: 0,
          mb: tagline || subtitle ? 1.75 : 0,
          display: 'flex',
          justifyContent: 'center',
          fontSize: 0,
          fontWeight: 400,
        }}
      >
        <BrandHomeLink>
          <BrandLogo variant="full" animate onDark={onDark} label={title} />
        </BrandHomeLink>
      </Box>
      {tagline ? (
        <Typography
          variant="h6"
          color="text.secondary"
          sx={{ fontWeight: 500, fontSize: { xs: '0.95rem', sm: '1.1rem' }, px: 1 }}
        >
          {tagline}
        </Typography>
      ) : null}
      {subtitle ? (
        <Typography variant="body2" color="text.secondary" sx={{ mt: tagline ? 1.5 : 0 }}>
          {subtitle}
        </Typography>
      ) : null}
    </Box>
  )
}
