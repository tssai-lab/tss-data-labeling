export function buildTrainingPlanHyperParams(plan) {
  const parameters = Array.isArray(plan?.parameters) ? plan.parameters : [];
  const values = {};

  for (const parameter of parameters) {
    const name =
      typeof parameter?.name === 'string' ? parameter.name.trim() : '';
    if (!name || parameter.defaultValue === undefined) continue;
    values[name] = parameter.defaultValue;
  }

  return JSON.stringify(values, null, 2);
}
