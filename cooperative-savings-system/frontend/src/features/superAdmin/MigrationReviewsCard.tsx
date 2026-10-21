import { Alert, Card, CardContent, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { fetchMigrationReviews } from '@/shared/api/migrationStrategy'

/** Saving Schemes whose historical migration needs review: they accept no further import until a fund authority resolves it. */
export function MigrationReviewsCard() {
  const { t } = useTranslation()
  const query = useQuery({ queryKey: ['platform', 'migration-reviews'], queryFn: fetchMigrationReviews })
  if (!query.data || query.data.length === 0) return null
  return (
    <Card variant="outlined" sx={{ mb: 3 }} data-testid="migration-reviews-card">
      <CardContent>
        <Typography variant="h6" gutterBottom>
          {t('migrationReview.platformTitle')}
        </Typography>
        <Alert severity="warning" sx={{ mb: 1 }}>
          {t('migrationReview.platformExplain')}
        </Alert>
        <Stack spacing={0.5}>
          {query.data.map((item) => (
            <Typography key={item.cooperativeId} variant="body2">
              {item.cooperativeName} — {t(`migrationReview.strategies.${item.strategy}`)}
            </Typography>
          ))}
        </Stack>
      </CardContent>
    </Card>
  )
}
